# Reproducible Build Procedure

This document describes the release evidence that must be produced from a clean
checkout. It is a procedure, not a claim that the current development branch is
reproducible. The current gate remains `BLOCKED` until an annotated release tag,
complete dependency evidence, and approved release identity exist.

## Inputs

- Use the repository's annotated release tag and a clean checkout with no ignored
  credentials, media, keystores, or generated native outputs.
- Use the pinned versions in `gradle/libs.versions.toml`,
  `gradle/verification-metadata.xml`, and `native-build/sources.lock.json`.
- Set `SOURCE_DATE_EPOCH` to the tag commit timestamp in the build environment.
  Do not record the local checkout path or any secret value.
- Run with the documented JDK/Android SDK/NDK logical inputs (`JDK_17`,
  `ANDROID_SDK`, and `NDK_R29`). The native toolchain manifest records versions,
  not machine paths.

## Native closure

Run the lock check and the two-clean-build comparison:

```text
python native-build/verify-lock.py
SOURCE_DATE_EPOCH=<tag-commit-epoch> native-build/verify-reproducible.sh
```

The script builds into isolated output directories, normalizes the work path,
hashes the include/lib tree, and compares both runs. The resulting
`reproducibility.sha256` belongs under the generated native evidence directory.
Every native source, compiler, linker, build option, ELF dependency, and notice
must be represented by the lock and evidence manifests before the license matrix
can become `PASS`.

## Android closure

Verify the Gradle inputs before building:

```text
python native-build/verify-release-inputs.py \
  --root . \
  --verification gradle/verification-metadata.xml \
  --matrix docs/compliance/license-matrix.json \
  --toolchain native-build/out-arm64/evidence/toolchain-manifest.json
```

Then build twice in separate clean output directories with the same
`SOURCE_DATE_EPOCH` and release properties. Normalize archive timestamps and
compare the resulting APK byte hashes. A matching hash is evidence only when the
source tag, dependency metadata, native evidence, application identity, and
signing identity are all the same.

The verification metadata must contain checksums for every resolved release
artifact and must not contain ignored-artifact rules or local file origins. If a
dependency report, native evidence, tag, or production identity is unavailable,
the corresponding gate is `BLOCKED`; an empty matrix or a debug APK is not a
substitute.

## Artifact checks

For the approved release APK, record only repository-relative artifact names and
SHA-256 values. Run:

```text
zipalign -c -P 16 -v 4 <release-apk>
apksigner verify --verbose --print-certs <release-apk>
```

Do not commit the APK, signing key, certificate private material, local paths,
or raw build logs. Delete disposable build directories and clear environment
variables after the evidence summary is written.
