# Native 依赖锁与许可证矩阵

本文件是 P1/T01 的人工审阅记录。机器可读事实以
`native-build/sources.lock.json` 为准；T02 只能从该锁指定的官方 URL、commit
或带 SHA-256 的归档获取源码，不得把相邻参考仓库直接当作构建输入。

## 1. 审阅结论

- ABI 首批只构建 `arm64-v8a`，Android native API 为 26，NDK 固定为
  `ndk;29.0.14206865`（r29），Meson 固定为 `1.11.2`。
- native 组合选择 GPL 路线。FFmpeg 必须同时启用 `--enable-gpl` 和
  `--enable-version3`；mpv 必须启用 `-Dgpl=true`；应用继续以
  `GPL-3.0-or-later` 发布。
- 禁止 `--enable-nonfree`、OpenSSL 和 FDK AAC。mbedTLS 在双许可证中选择
  Apache-2.0，靠 FFmpeg 的 `--enable-version3` 与 GPLv3 组合。
- mpv 和 FFmpeg 生成共享库；字幕、渲染、字体、AV1、TLS、XML、Lua 和 curl
  依赖静态并入这些共享库。由于 libplacebo 包含 C++ 代码，T02 必须同时从同一
  NDK r29 交付 `libc++_shared.so`，登记哈希和 NOTICE；T03 的 JNI adapter 复用它。
- 所有下载均需先校验锁内 commit 或 SHA-256。构建后必须用 `llvm-readelf`
  物化实际 `DT_NEEDED`，未在矩阵中的非 Android 系统库会使 T02 失败。

## 2. 输入追溯

| 组件 | 锁定版本/revision | 链接/用途 | 选择的许可证 | NOTICE 来源 |
| --- | --- | --- | --- | --- |
| mpv-android | `b44d3addc2a6bfb140a862308955a836e4a922d2` | 只读构建与 Android 适配参考，不进入 T02 二进制 | MIT | `LICENSE` |
| mpv | `dd5d17d3285a095a0f712fa9d116e22a076492de` | `libmpv.so` | GPL-2.0-or-later，组合按 GPL-3.0-or-later | `Copyright`, `LICENSE.GPL` |
| FFmpeg | `f944afd04097178b7e3c0d6c7f4e524a9e8f6063` | 多个 `libav*.so` | GPL-3.0-or-later | `LICENSE.md`, `COPYING.GPLv3`，并保留 LGPL 文本 |
| dav1d | `1.5.4` / `54706fc6bc0cdecab7e9593974a4039cc038fca7` | 静态并入 FFmpeg | BSD-2-Clause | `COPYING` |
| libass | `89cc0f4e450d64f74281a17d7f11ed05229665e8` | 静态并入 libmpv | ISC | `COPYING` |
| libplacebo | `4d82c6898551068d4ae6a6b5538efcddc2c7cf64` | OpenGL 路径，静态并入 libmpv | LGPL-2.1-or-later | `LICENSE` |
| FreeType | `VER-2-14-3` / `0a0221a1347e2f1e07c395263540026e9a0aa7c7` | libass 字体栅格化 | GPL-2.0-or-later（不选 FTL） | `LICENSE.TXT`, `docs/GPLv2.TXT` |
| FriBidi | 1.0.16 / 锁内归档哈希 | libass 双向文本 | LGPL-2.1-or-later | `COPYING` |
| HarfBuzz | 14.2.1 / 锁内归档哈希 | libass 文本整形 | MIT | 完整 `COPYING`（含子组件声明） |
| libunibreak | 7.0 / 锁内归档哈希 | libass 换行 | Zlib | `LICENCE` |
| fontconfig | 2.18.2 / 锁内归档哈希 | libass 字体发现 | MIT | 完整 `COPYING`（含 Unicode 数据声明） |
| libxml2 | 2.15.3 / 锁内归档哈希 | FFmpeg 与 fontconfig XML | MIT | `Copyright` |
| mbedTLS | 3.6.7 / 锁内归档哈希 | FFmpeg/curl TLS | Apache-2.0 | `LICENSE` |
| Lua | 5.2.4 / 锁内归档哈希 | 静态并入 libmpv | MIT | `doc/readme.html` |
| curl | 8.21.0 / 锁内归档哈希 | libmpv 网络流，SMB 协议禁用 | curl | `COPYING` |
| fast_float | `97b54ca9e75f5303507699d27c6b4f4efe4641a1` | libplacebo 头文件代码 | MIT | `LICENSE-MIT` |
| glad | `73db193f853e2ee079bf3ca8a64aa2eaf6459043` | 生成/编译 OpenGL loader | MIT 与 Khronos/Apache-2.0 声明 | `LICENSE` |
| Jinja | `15206881c006c79667fe5154fe80c01c65410679` | libplacebo 生成器，仅构建时 | BSD-3-Clause | `LICENSE.txt` |
| MarkupSafe | `297fc8e356e6836a62087949245d09a28e9f1b13` | Jinja 依赖，仅构建时 | BSD-3-Clause | `LICENSE.txt` |
| Android NDK | r29 / `5199c56421d79df5099aad8e32e32c101ff85cca` | Clang/LLD/sysroot，T02 提供 `libc++_shared.so` | Apache-2.0 WITH LLVM-exception 等，按包内 NOTICE | NDK 根 `NOTICE` 与 `NOTICE.toolchain` |

归档的确切 URL 和 SHA-256 不在本表重复，避免文档与机器锁双写漂移。
`native-build/verify-lock.py` 会验证所有归档哈希字段、Git revision、许可证和
NOTICE 路径字段均存在。

## 3. 编译配置边界

FFmpeg 以共享库形式构建，启用 JNI、MediaCodec、mbedTLS、dav1d 和 libxml2；
禁用程序、文档、设备、默认 muxer/encoder 集合与 Vulkan，只重新启用截图需要的
MJPEG/PNG encoder 和缓存导出需要的 MOV/Matroska/MPEG-TS muxer。构建产物的
`config.h`、`config.mak` 和最终 `ffmpeg -buildconf` 等价信息必须进入 T02 清单。

mpv 只构建 libmpv，不构建 CLI；启用 Lua、curl、Android Media NDK 和 plain
OpenGL，禁用 Vulkan、manpage 和 tests。libplacebo 同样显式禁用 Vulkan、SPIR-V
编译器、demo、tests、bench、fuzz、lcms、Dolby Vision 外部库、xxhash 和 unwind，
避免宿主机偶然安装的库改变闭包。

所有 Meson 构建必须使用 `--wrap-mode=nodownload`，所有 `pkg-config` 查询只能看
目标 prefix。T02 不得自动回退到宿主机库，也不得在构建过程中下载未锁定 wrap。

## 4. 预期二进制闭包

T02 预期交付 `libmpv.so`、`libavcodec.so`、`libavfilter.so`、
`libavformat.so`、`libavdevice.so`、`libavutil.so`、`libswresample.so`、`libswscale.so` 和
`libc++_shared.so`。
dav1d、libass、libplacebo、字体栈、mbedTLS、libxml2、Lua 和 curl 不应作为独立
`.so` 打包；若 `readelf` 发现它们仍是动态依赖，必须修正构建或更新本矩阵并重新
审查。

Android 平台的 `libandroid.so`、`liblog.so`、`libdl.so`、`libm.so`、`libz.so`、
`libEGL.so`、`libGLESv2.so`、`libmediandk.so`、`libOpenSLES.so` 和 Bionic libc
可作为系统库出现，但仍须由 T02 的 `elf-dependencies.json` 记录。T03 生成的
`libplayer.so` 不属于 T02 产物，并复用 T02 已审计的同版 `libc++_shared.so`。

## 5. T02 强制证据

一次合格的构建必须同时输出以下材料，缺一不可：

1. 每个源码目录的最终 URL、commit 或已验证归档 SHA-256；
2. NDK package revision、Clang/LLD 版本、NDK source revision 和包内 NOTICE；
3. 每个组件的完整实际 configure/Meson 参数与环境摘要；
4. 所有 `.so` 的 SHA-256、SONAME、`DT_NEEDED`、ELF machine 和 16 KiB page 对齐；
5. `libmpv` public headers 与本文件列出的全部许可证/NOTICE 原文；
6. 从空输出目录执行两次所得的内容哈希比较。若上游会嵌入时间戳，必须记录并
   固定 `SOURCE_DATE_EPOCH`，不能只声明“可重复”。

T01 只锁定输入和合规边界，不把第三方源码或许可证全文复制进产品仓库；T32 再从
实际发布构建生成 `THIRD_PARTY_LICENSES` 和最终 NOTICE。
