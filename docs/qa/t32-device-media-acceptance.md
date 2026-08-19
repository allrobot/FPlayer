# T32 Device And Media Acceptance

Date: 2026-08-20

This report contains aggregate, redacted evidence only. Runtime endpoints, credentials, device addresses, serial names, media names, locators and frame payloads are intentionally omitted.

## Loopback And Synthetic Gates

| Gate | State | Evidence |
| --- | --- | --- |
| `DEVICE-LOOPBACK` | PASS | Injected TCP/UDP/WebSocket, BLE, SPP and USB transport suites passed with bounded queues, deterministic failure codes, emergency-stop ordering and no stale replay. |
| `MEDIA-SYNTHETIC` | PASS | SAF/SMB generation tests passed: incomplete work generations do not replace the committed snapshot; I/O failures are redacted. |

## SMB-REAL

| Field | Result |
| --- | --- |
| Logical source | `FUNSCRIPT_TEST_LIBRARY` |
| Authentication | PASS; missing credentials are blocked and both roots ran through the credentialed path |
| Initial scan | PASS; both roots reached online committed generations |
| Video/script matching | PASS; root A aggregate `28/28/28`, root B aggregate `8/38/8` for media/total scripts/same-stem matches |
| Controlled connection cut | PASS; the real proxy closes before the first listing and SMBJ observes the socket interruption without an injected exception |
| Snapshot retention | PASS; generation 2 failed/incomplete while generation 1 remained current |
| Recovery generation | PASS; reconnecting the direct credentialed source committed generation 3 |
| Credential cleanup | PASS; runtime secret buffer and credential-store entry were cleared after each run |
| Evidence policy | PASS; only aggregate counts and logical identifiers are retained; non-secret locator/auth-reference assertions are confined to the in-memory test database |

The first real run exposed a test-fixture defect: closing a proxy after a complete flat-root listing did not necessarily interrupt a later SMB operation. The fixture now closes the proxy before the first delegated listing and relies on the production SMBJ operation to observe the socket interruption; the injected flat-tree failure remains a separate unit regression. Both authorized roots passed the corrected host gate.

## TEST_TABLET Media

| Gate | State | Blocker |
| --- | --- | --- |
| `MEDIA-SD-MULTIAXIS` | BLOCKED | No ADB-visible tablet or persisted read-only SAF grant in this run. |
| `MEDIA-SD-SINGLEAXIS` | BLOCKED | No ADB-visible tablet or persisted read-only SAF grant in this run. |

## TEST_OSR_DEVICE Transports

| Gate | State | Blocker |
| --- | --- | --- |
| `DEVICE-WS` | BLOCKED | No ADB-visible tablet and no uniquely accepted runtime profile. |
| `DEVICE-SPP` | BLOCKED | No uniquely accepted paired target/profile. |
| `DEVICE-BLE` | BLOCKED | No uniquely accepted candidate/profile. |
| `DEVICE-USB` | BLOCKED | No Android USB descriptor and permission confirmation. |

No physical TCode motion was attempted. The unloaded/visible/immediate-cutoff/unique-profile gate therefore remains intact.

## Cleanup

- Runtime SMB credential buffers were cleared and the credential entry deleted.
- No real media was copied or modified.
- No Android package, device setting, firmware or persistent device configuration was changed.
- ADB/device and physical transport cleanup remains pending until a real target is present.
