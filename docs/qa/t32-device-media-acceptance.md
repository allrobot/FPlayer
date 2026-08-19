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
| Authentication | PASS; runtime-only credential store path |
| Initial scan | PASS; online result and committed generation |
| Video/script matching | PASS; expected aggregate media count and same-stem matching |
| Controlled connection cut | PASS; failed generation reports `SMB_IO_FAILED` |
| Snapshot retention | PASS; prior committed generation and aggregate count remain current |
| Credential cleanup | PASS; in-memory credential cleared and deleted after the run |
| Evidence policy | PASS; no runtime endpoint, credential, locator or media filename persisted |

The first real run exposed a test-fixture defect: closing a proxy after a complete flat-root listing did not necessarily interrupt a later SMB operation. The acceptance fixture now closes the proxy and raises a deterministic I/O interruption at the first listing boundary, preserving the product-level generation/rollback assertion without relying on directory shape.

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
