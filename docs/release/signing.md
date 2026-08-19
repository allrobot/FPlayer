# Release Identity And Signing

FPlayer development builds use the isolated development application ID and the
standard Android debug signing flow. A release build must never use the debug
keystore, the development version, or a key checked into this repository.

## Required Inputs

Supply all values through Gradle properties or process environment. Secret values
must come from local secret storage or CI secret storage and must not be printed:

| Input | Purpose |
| --- | --- |
| `FPLAYER_APPLICATION_ID` | Approved production application identity |
| `FPLAYER_VERSION_CODE` | Positive production version code |
| `FPLAYER_VERSION_NAME` | Non-development release version |
| `FPLAYER_RELEASE_STORE_FILE` | Existing external keystore path |
| `FPLAYER_RELEASE_STORE_PASSWORD` | Keystore secret |
| `FPLAYER_RELEASE_KEY_ALIAS` | Approved production key alias |
| `FPLAYER_RELEASE_KEY_PASSWORD` | Key secret |

The release Gradle tasks run `validateReleaseConfiguration` before producing an
artifact. Missing or invalid identity/signing values fail the build. The release
build type is attached only to the explicit release signing configuration; it
does not fall back to debug signing.

## Artifact Verification

After an approved release build, verify the artifact with the SDK build tools:

```text
zipalign -c -P 16 -v 4 <release-apk>
apksigner verify --verbose --print-certs <release-apk>
```

Record the SHA-256 of the APK and the public signing-certificate fingerprint in
the release evidence. Do not record passwords, private-key material, local
paths, environment dumps, or command output containing secret values. A test key
may validate the wiring in a disposable environment, but cannot satisfy
`RELEASE-SIGN`; only the owner-approved production identity can do so.

## Cleanup

After verification, clear all `FPLAYER_*` signing and identity variables from the
process, delete disposable keystores and build directories outside the repository,
and run the release hygiene scan. Do not commit APKs, keystores, certificates with
private material, or generated signing reports. Without approved production inputs,
the gate remains `BLOCKED`.
