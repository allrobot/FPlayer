# Task 3 Implementation Report

Status: DONE_WITH_EXTERNAL_BLOCKER

## Commits

- `65f8fec` aligns runtime-gated host and instrumentation acceptance with the T32 environment contract and adds a redacted injected SMB I/O failure regression.
- `534bb7d` makes the controlled disconnect boundary deterministic for flat sources in both host and instrumentation fixtures.
- `c212ce6` records aggregate-only host acceptance evidence and updates the T32 matrix/index.

## Root Cause

The original connection-cut wrapper closed its TCP proxy after the first directory listing returned. A flat source can complete discovery without another SMB operation, so the scan could legitimately commit and the fixture's expected failure was not guaranteed. The wrapper now closes the proxy and surfaces an I/O interruption at that same listing boundary; the generation rollback contract is asserted independently of directory shape.

## Verification

- JDK 17 focused synthetic SMB scanner regression: PASS.
- JDK 17 broad SMB/SAF host regression: PASS with runtime gate disabled where expected.
- JDK 17 authenticated runtime host acceptance: PASS; direct scan committed, controlled interruption returned `SMB_IO_FAILED`, the work generation remained incomplete, and the prior snapshot remained current.
- Credential cleanup: runtime password buffer cleared; credential entry deleted in the fixture's `finally` block.
- Sensitive-data scan and `git diff --check` for QA evidence: PASS.

## External Blocker

The matching Android instrumentation run remains BLOCKED because no `TEST_TABLET` is visible through ADB. This does not change the host `SMB-REAL` result; the blocker is retained explicitly in the acceptance report.

## Concerns

None for the host acceptance boundary. The deterministic wrapper is test-only and does not alter production SMB behavior.

## Review Fix Round 1 (2026-08-20)

- Authentication is mandatory when the runtime gate is enabled. Missing or blank runtime username/password now reports the stable `REAL_SMB_ACCEPTANCE_BLOCKED_CREDENTIALS` assumption rather than falling back to anonymous SMB auth. The instrumentation equivalent uses `REAL_SMB_DEVICE_ACCEPTANCE_BLOCKED_CREDENTIALS`.
- The real connection-cut wrapper now closes the proxy before the first delegated SMB listing and does not throw an injected exception. Therefore a passing runtime result requires SMBJ to observe an actual socket interruption. The separate `FlatDisconnectingTree` unit fixture retains injected-I/O coverage for generation rollback without representing real socket evidence.
- The real host and instrumentation flows now reconnect through the direct credentialed source after the failed generation and require generation 3 to commit while generation 2 remains incomplete.
- Runtime inputs now include redacted numeric `FPLAYER_T32_SMB_SCRIPT_COUNT` / `expected_scripts`. Direct and recovery scans require that script count and require every accepted script to have a same-stem media match. No filenames or locators are emitted.
- The source row intentionally persists the non-secret SMB root locator and logical credential reference. The acceptance assertions prove those exact non-secret values; this report makes no claim that the locator is absent from persistence. Username/password are neither asserted from persistence nor recorded.

### Round 1 Verification

- RED: `SmbAcceptanceRuntimePolicyTest` initially failed to compile because the mandatory-credential policy did not exist.
- GREEN: JDK 17 `:core:index:testDebugUnitTest --tests '*SmbAcceptanceRuntimePolicyTest' --tests '*SmbScannerTest' --no-configuration-cache --rerun-tasks --max-workers=1` passed.
- GREEN: JDK 17 `:core:index:compileDebugAndroidTestJavaWithJavac --no-configuration-cache --rerun-tasks --max-workers=1` passed.
- The current process has no T32 SMB runtime variables, so the post-fix credentialed host gate is BLOCKED locally rather than run with fabricated credentials. No credentials, external SMB resources, or device resources were read in this round.
