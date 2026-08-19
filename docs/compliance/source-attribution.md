# Source Attribution and Ownership

This document records ownership boundaries for FPlayer release review. FPlayer-owned
source is licensed under `GPL-3.0-or-later`; the complete license text is in
`LICENSE`. Third-party release closure is controlled by
`docs/compliance/license-matrix.json`, with human-readable notices in `NOTICE` and
`THIRD_PARTY_LICENSES`.

The current license matrix is `BLOCKED` because its sanitized Gradle resolution
input is unavailable. The component list below describes the required review
scope, not a completed release-license claim.

## Attribution record contract

Each source-attribution record uses these fields:

- `component_id`: stable FPlayer or `REF_*` logical identifier.
- `relationship`: `owned`, `copied-or-modified`, or `semantic-reference`.
- `license`: the reviewed license expression or `not-distributed`.
- `revision`: the reviewed source revision, or `observation-only` when no source
  is copied.
- `files_or_scope`: repository-relative files or a bounded logical scope.
- `notice_ref`: repository-relative review evidence.

| component_id | relationship | license | revision | files_or_scope | notice_ref |
| --- | --- | --- | --- | --- | --- |
| `FPLAYER_APP` | `owned` | `GPL-3.0-or-later` | repository tag | `app/` | `LICENSE` |
| `FPLAYER_CORE` | `owned` | `GPL-3.0-or-later` | repository tag | `core/` | `LICENSE` |
| `FPLAYER_FEATURES` | `owned` | `GPL-3.0-or-later` | repository tag | `feature/` | `LICENSE` |
| `FPLAYER_BUILD_DOCS` | `owned` | `GPL-3.0-or-later` | repository tag | Gradle configuration, `native-build/`, and `docs/` | `LICENSE` |
| `REF_MPV_ANDROID` | `semantic-reference` | `not-distributed` | `observation-only` | Android playback integration behavior | `docs/references/source-map.md` |
| `REF_MPV` | `semantic-reference` | `not-distributed` | source-map managed | native player behavior; the release source is independently acquired from the native lock | `docs/references/source-map.md` |
| `REF_FFMPEG` | `semantic-reference` | `not-distributed` | source-map managed | codec build behavior; the release source is independently acquired from the native lock | `docs/references/source-map.md` |
| `REF_DAV1D` | `semantic-reference` | `not-distributed` | source-map managed | decoder behavior; the local snapshot is not a build input | `docs/references/source-map.md` |
| `REF_LIBASS` | `semantic-reference` | `not-distributed` | source-map managed | subtitle behavior; the release source is independently acquired from the native lock | `docs/references/source-map.md` |
| `REF_LIBPLACEBO` | `semantic-reference` | `not-distributed` | source-map managed | renderer behavior; the release source is independently acquired from the native lock | `docs/references/source-map.md` |
| `REF_AYVA_JS` | `semantic-reference` | `not-distributed` | `observation-only` | TCode behavior and naming semantics | `docs/references/source-map.md` |
| `REF_AYVA_WS_HUB` | `semantic-reference` | `not-distributed` | `observation-only` | raw WebSocket interoperability | `docs/references/source-map.md` |
| `REF_TCODE_FIRMWARE` | `semantic-reference` | `not-distributed` | source-map managed | protocol and transport behavior | `docs/references/source-map.md` |
| `REF_OSR_EMULATOR` | `semantic-reference` | `not-distributed` | `observation-only` | motion visualization behavior | `docs/references/source-map.md` |
| `REF_DORO_PLAYER` | `semantic-reference` | `not-distributed` | `observation-only` | comparative player behavior | `docs/references/source-map.md` |
| `REF_MULTI_FUN_PLAYER` | `semantic-reference` | `not-distributed` | `observation-only` | multi-axis synchronization behavior | `docs/references/source-map.md` |
| `REF_INTIFACE_CENTRAL` | `semantic-reference` | `not-distributed` | `observation-only` | future adapter behavior outside the first release scope | `docs/references/source-map.md` |
| `REF_FUNSCRIPT_FLOW_UP` | `semantic-reference` | `not-distributed` | `observation-only` | script playback behavior | `docs/references/source-map.md` |

There are no accepted `copied-or-modified` records at this checkpoint. No source,
assets, branding, or media from a reference application or reference webpage are
shipped by FPlayer. Behavioral comparison is observation evidence only and does
not establish ownership or permission to copy an implementation.

## Native and Gradle closure

Native attribution is recorded in the matrix rather than duplicated with mutable
revisions in this document:

| matrix component IDs | disposition | authority |
| --- | --- | --- |
| `android-ndk` | toolchain runtime | `docs/compliance/license-matrix.json` |
| `mpv-android-reference` | reference-only, not shipped | `docs/compliance/license-matrix.json` |
| `mpv`, `ffmpeg` | runtime shared libraries | `docs/compliance/license-matrix.json` |
| `dav1d`, `libass`, `libplacebo`, `freetype`, `mbedtls`, `fribidi`, `harfbuzz`, `libunibreak`, `libxml2`, `fontconfig`, `lua`, `curl` | runtime static inputs | `docs/compliance/license-matrix.json` |
| `fast-float`, `glad` | runtime source inputs | `docs/compliance/license-matrix.json` |
| `jinja`, `markupsafe` | build-only inputs | `docs/compliance/license-matrix.json` |

Their individual revisions, roles, linkage, selected licenses, and original notice
paths are authoritative only when joined successfully from
`native-build/sources.lock.json` and native evidence into
`docs/compliance/license-matrix.json`. The reference-only Android integration
component stays indexed but is not represented as shipped source.

Gradle components must appear in the same matrix from a sanitized resolved
dependency report. Missing Gradle metadata keeps `RELEASE-LICENSE` `BLOCKED`;
declared versions or an empty component list are not replacement evidence.

## Review rule for future copying

Before accepting copied or modified third-party implementation, the reviewer must
add a `copied-or-modified` row that identifies a registered `REF_*` source-map ID,
an immutable revision, exact repository-relative changed files, license
compatibility, required notice text, and review evidence. Missing any field blocks
the change from a release. Updating only `NOTICE` does not satisfy this rule.
