# FPlayer Privacy and Local Data Handling

FPlayer is offline-first. Normal playback, indexing, script synchronization,
device control, and recommendation processing run on the Android device. There is
no analytics upload, advertising telemetry, cloud profiling, or remote account
service in the product boundary.

## Data classes

| DataClass | Data | Retention | Transport |
| --- | --- | --- | --- |
| `media-metadata` | User-selected SAF and SMB source metadata, index state, script-match state, thumbnails, and playback position | App-private storage until the user removes a source, clears the related cache, resets application data, or uninstalls | Read only from a user-selected SAF or SMB scope; not uploaded by FPlayer |
| `credentials` | SMB authentication material and transport authorization state | Keystore-protected local storage for a saved source; decrypted values exist only for the active operation and buffers are cleared afterward | Sent only to the user-selected source protocol; never written to repository assets or diagnostics |
| `playback-recommendation-events` | Local play, pause, seek, completion, repeat, preference, and ranking aggregates | App-private storage until history/profile reset, application data reset, or uninstall | No network transport and no analytics upload |
| `diagnostics` | Status codes, generations, counts, timings, and bounded failure context | Ephemeral runtime logs or user-requested sanitized evidence; no raw long-term device command stream | Redacted diagnostics and logs remove source names, network identifiers, credentials, device identifiers, and local paths before sharing |
| `device-session` | Runtime transport profile, negotiated capability, latency estimate, and safety state | Memory for the active connection unless the user explicitly saves a non-secret profile | Limited to the selected device transport; no cloud relay is implied |

## Source access and retention

SAF access begins only after the user selects a tree and grants read scope. SMB
access begins only after the user selects or configures a source. FPlayer does not
perform a general device-storage scan or expand access beyond that chosen scope.
Removing a source removes its active index relationship; cache cleanup targets
only app-owned derived data and never deletes original media.

SMB secrets are protected with Android Keystore-backed encryption. Plaintext
credential values are runtime-only, are not stored in the media database, and are
not included in exported diagnostics. Device and network endpoints follow the
same runtime-only rule unless a user explicitly saves a non-secret connection
profile in app-private storage.

Recommendation events remain local and are used only by the deterministic
on-device ranking described in `docs/architecture/recommendation.md`. Resetting
the recommendation profile deletes those aggregates without changing media.

## Diagnostics and testing

Operational logs use stable error codes and redacted logical context. A shared
diagnostic must contain sanitized logs, counts, hashes, or gate states only; it
must not contain credentials, source names, media content, raw device frames, or
host paths.

Repository tests use synthetic neutral fixtures. Real test resources are referred
to only as `TEST_TABLET`, `TEST_OSR_DEVICE`, `TEST_OSR_WIFI`, and
`FUNSCRIPT_TEST_LIBRARY`. Real media, thumbnails, scripts, credentials, and device
captures are never retained as repository fixtures. Test cleanup stops playback
and device output, closes sessions, clears runtime credential buffers, and removes
only app-owned temporary data.

## User control

Users can stop device output independently of playback, disconnect a transport,
remove a media source, clear derived caches, reset recommendation history, clear
application data, or uninstall FPlayer. These actions must not delete content in a
user-selected source unless a separate, explicit product operation is introduced
and confirmed.
