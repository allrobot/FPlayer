#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/native-build/sources.lock.json"
LOCK_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["lock_id"])' "$LOCK")"
NDK_VERSION="ndk;29.0.14206865"
MESON_VERSION="1.11.2"
ANDROID_API="26"
CORES="${CORES:-$(nproc)}"
OUT="${OUT:-$ROOT/native-build/out-arm64}"
WORK="${WORK:-$ROOT/native-build/work-arm64}"
CACHE="${CACHE:-$ROOT/native-build/cache}"
SOURCE_ROOT="$WORK/sources"
PREFIX="$WORK/prefix"
EVIDENCE="$OUT/evidence"
CROSS_FILE="$ROOT/native-build/android-arm64.cross"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v python3 >/dev/null || die "python3 is required"
command -v git >/dev/null || die "git is required"
command -v meson >/dev/null || die "meson is required"
command -v ninja >/dev/null || die "ninja is required"
command -v curl >/dev/null || die "curl is required"
[[ "$(meson --version)" == "$MESON_VERSION" ]] || die "Meson $MESON_VERSION is required"
python3 "$ROOT/native-build/verify-lock.py"
mkdir -p "$(dirname "$WORK")"
available_bytes="$(df -P -B1 "$(dirname "$WORK")" | awk 'NR==2 {print $4}')"
(( available_bytes >= 35000000000 )) || die "at least 35 GB must be free on the builder filesystem"
swap_bytes="$(free -b | awk '/^Swap:/ {print $2}')"
(( swap_bytes >= 4000000000 )) || die "at least 4 GB swap is required"

NDK="${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-}/ndk/29.0.14206865}"
[[ -f "$NDK/source.properties" ]] || die "NDK r29 not found; install $NDK_VERSION or set ANDROID_NDK_ROOT"
grep -q 'Pkg.Revision = 29.0.14206865' "$NDK/source.properties" || die "unexpected NDK revision"

for path in "$OUT" "$WORK"; do
  [[ -n "$path" && "$path" != / && "$path" != "$ROOT" && "$path" != "${HOME:-}" ]] || die "unsafe generated directory: $path"
  [[ ! -e "$path" ]] || die "generated directory already exists: $path"
done
mkdir -p "$OUT" "$WORK" "$EVIDENCE" "$PREFIX"
python3 "$ROOT/native-build/prepare-inputs.py" --lock "$LOCK" --source-root "$SOURCE_ROOT" --cache "$CACHE" --manifest "$EVIDENCE/source-manifest.json"

export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
command -v llvm-readelf >/dev/null || die "llvm-readelf is missing from the NDK"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-0}"
export PKG_CONFIG_SYSROOT_DIR="$PREFIX"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH=
export CC="aarch64-linux-android${ANDROID_API}-clang"
export CXX="aarch64-linux-android${ANDROID_API}-clang++"
export AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip NM=llvm-nm
PREFIX_MAP_FLAGS="-ffile-prefix-map=$WORK=. -fdebug-prefix-map=$WORK=. -fmacro-prefix-map=$WORK=."
export CFLAGS="-I$PREFIX/include -fPIC $PREFIX_MAP_FLAGS"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-Wl,-O1,--icf=safe -Wl,-z,max-page-size=16384 -L$PREFIX/lib"

ln -s . "$PREFIX/usr"
ln -s . "$PREFIX/local"
cat > "$CROSS_FILE" <<CROSSFILE
[built-in options]
buildtype = 'release'
default_library = 'static'
wrap_mode = 'nodownload'
prefix = '/usr/local'
[binaries]
c = '$CC'
cpp = '$CXX'
ar = 'llvm-ar'
nm = 'llvm-nm'
strip = 'llvm-strip'
pkgconfig = 'pkg-config'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
CROSSFILE

build_meson() { local id="$1"; shift; meson setup "$WORK/build-$id" "$SOURCE_ROOT/$id" --cross-file "$CROSS_FILE" --prefix=/usr/local --buildtype=release --default-library=static --wrap-mode=nodownload "$@"; ninja -C "$WORK/build-$id" -j"$CORES"; DESTDIR="$PREFIX" ninja -C "$WORK/build-$id" install; }
build_autotools() { local id="$1"; shift; pushd "$SOURCE_ROOT/$id" >/dev/null; [[ -x configure ]] || ./autogen.sh; mkdir -p "$WORK/build-$id"; pushd "$WORK/build-$id" >/dev/null; "$SOURCE_ROOT/$id/configure" --host=aarch64-linux-android --with-pic "$@"; make -j"$CORES"; make DESTDIR="$PREFIX" install; popd >/dev/null; popd >/dev/null; }

make -C "$SOURCE_ROOT/mbedtls" -j"$CORES" no_test
make -C "$SOURCE_ROOT/mbedtls" DESTDIR="$PREFIX" install
build_meson dav1d -Denable_tests=false -Denable_tools=false -Db_lto=true -Dstack_alignment=16
build_meson freetype
build_meson fribidi -Dtests=false -Ddocs=false
build_meson harfbuzz -Dtests=disabled -Ddocs=disabled -Draster=disabled -Dvector=disabled -Dgpu=disabled -Dsubset=disabled
build_autotools libunibreak --with-pic --enable-static --disable-shared
build_meson libxml2 -Dminimum=true -Dpush=enabled -Dreader=enabled -Dsax1=enabled -Diso8859x=enabled -Dpattern=enabled
build_meson fontconfig -Dtests=disabled -Ddoc=disabled -Dtools=disabled -Dnls=disabled -Dxml-backend=libxml2
build_autotools libass --enable-static --disable-shared --enable-libunibreak --enable-fontconfig
build_autotools curl --with-mbedtls="$PREFIX" --without-libpsl --disable-shared --enable-static --disable-debug --disable-manual --disable-docs --disable-ares --disable-unix-sockets --disable-tls-srp --disable-doh --disable-rtsp --disable-dict --disable-telnet --disable-tftp --disable-pop3 --disable-imap --disable-smb --disable-smtp --disable-gopher --disable-mqtt --disable-ntlm
make -C "$SOURCE_ROOT/lua" clean >/dev/null || true
make -C "$SOURCE_ROOT/lua" CC="$CC" AR="$AR rc" RANLIB="$RANLIB" MYCFLAGS='-fPIC -Dgetlocaledecpoint\(\)=\(46\) -Dlua_fseek' PLAT=linux LUA_T= LUAC_T= -j"$CORES"
make -C "$SOURCE_ROOT/lua" INSTALL=install INSTALL_TOP="$PREFIX" TO_BIN=/dev/null install
mkdir -p "$PREFIX/lib/pkgconfig"; printf 'prefix=/usr/local\nlibdir=${prefix}/lib\nincludedir=${prefix}/include\nName: Lua\nDescription: Lua runtime\nVersion: 5.2.4\nLibs: -L${libdir} -llua\nCflags: -I${includedir}\n' > "$PREFIX/lib/pkgconfig/lua.pc"
build_meson libplacebo -Dvulkan=disabled -Dopengl=enabled -Dgl-proc-addr=enabled -Ddemos=false -Dtests=false -Dbench=false -Dfuzz=false -Dshaderc=disabled -Dglslang=disabled -Dlcms=disabled -Ddovi=disabled -Dlibdovi=disabled -Dxxhash=disabled -Dunwind=disabled
mkdir -p "$WORK/build-ffmpeg"; pushd "$WORK/build-ffmpeg" >/dev/null
"$SOURCE_ROOT/ffmpeg/configure" --prefix=/usr/local --target-os=android --enable-cross-compile --cross-prefix=aarch64-linux-android- --cc="$CC" --pkg-config=pkg-config --nm=llvm-nm --arch=aarch64 --cpu=armv8-a --enable-jni --enable-mediacodec --enable-mbedtls --enable-libdav1d --enable-libxml2 --disable-vulkan --disable-static --enable-shared --enable-gpl --enable-version3 --disable-debug --disable-stripping --disable-doc --disable-programs --disable-muxers --disable-encoders --disable-devices --enable-encoder=mjpeg,png --enable-muxer=mov,matroska,mpegts
make -j"$CORES"; make DESTDIR="$PREFIX" install; popd >/dev/null
meson setup "$WORK/build-mpv" "$SOURCE_ROOT/mpv" --cross-file "$CROSS_FILE" --prefix=/usr/local --buildtype=release --default-library=shared --wrap-mode=nodownload -Dgpl=true -Diconv=disabled -Dlua=enabled -Dlibcurl=enabled -Dlibmpv=true -Dcplayer=false -Dmanpage-build=disabled -Dvulkan=disabled -Dplain-gl=enabled -Dandroid-media-ndk=enabled -Dtests=false
ninja -C "$WORK/build-mpv" -j"$CORES"; DESTDIR="$PREFIX" ninja -C "$WORK/build-mpv" install

cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$PREFIX/lib/"

# Installed pkg-config/libtool metadata is not shipped in the APK; normalize its
# private build prefix so the complete evidence tree remains reproducible.
while IFS= read -r metadata; do
  sed -i "s|$PREFIX|/usr/local|g" "$metadata"
done < <(find "$PREFIX/lib" -type f \( -name '*.pc' -o -name '*.la' \))

cp "$LOCK" "$EVIDENCE/build-options.json"
python3 "$ROOT/native-build/collect-evidence.py" --prefix "$PREFIX" --output "$EVIDENCE" --lock "$LOCK" --source-manifest "$EVIDENCE/source-manifest.json" --source-root "$SOURCE_ROOT" --ndk "$NDK"
cp -a "$PREFIX/include" "$OUT/"
cp -a "$PREFIX/lib" "$OUT/"
printf 'T02 COMPLETE: %s\n' "$OUT"
