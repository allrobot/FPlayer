# Release Gates

Release evidence uses four states: `PASS`, `FAIL`, `BLOCKED`, and `NOT_RUN`.
`PASS` requires direct evidence for every prerequisite. `FAIL` means the gate ran
and violated an expectation. `BLOCKED` means a required owner input or evidence
artifact is absent. `NOT_RUN` means prerequisites exist but the gate has not run.
No debug artifact, synthetic identity, empty inventory, or untagged checkout may
substitute for production evidence.

Each gate record has `gate_id`, `status`, `evidence`, `owner_input`, and `cleanup`.
Evidence paths below are repository-relative; external systems and test resources
use logical identifiers only.

| gate_id | status | evidence | owner_input | cleanup |
| --- | --- | --- | --- | --- |
| `RELEASE-LICENSE` | `BLOCKED` | `docs/compliance/license-matrix.json`, `NOTICE`, `THIRD_PARTY_LICENSES`, `docs/compliance/source-attribution.md` | Complete sanitized Gradle dependency/license report and reviewed dependency closure | Remove only task-specific staging files after verified atomic replacement; retain reviewed release assets |
| `RELEASE-REPRO` | `BLOCKED` | `docs/release/reproducible-build.md`, `native-build/sources.lock.json`, native evidence manifests | Annotated release tag, clean source archive, complete dependency verification, and approved release identity | Remove isolated build directories after hashes and sanitized summaries are recorded |
| `RELEASE-SIGN` | `BLOCKED` | `docs/release/signing.md` when available, verified release artifact summary | Approved application/organization identity and production signing identity supplied through local or CI secret storage | Clear signing environment, delete disposable key material and untracked staging files, retain no secret values |
| `RELEASE-HYGIENE` | `NOT_RUN` | Hygiene scan result, `git diff --check`, tracked-artifact inventory | Complete candidate release tree | Remove scan staging and generated logs after retaining category/count summaries |
| `RELEASE-TAG` | `BLOCKED` | Annotated tag verification and clean tag checkout summary | Approved annotated release tag naming the candidate commit | Remove the isolated checkout only after evidence is recorded; never create a tag solely to satisfy a test |

## Fail-closed rules

- A missing annotated release tag always yields `BLOCKED` for `RELEASE-TAG` and
  `RELEASE-REPRO`; a branch name or lightweight tag is not sufficient.
- A missing production signing identity always yields `BLOCKED` for
  `RELEASE-SIGN`; a debug key, temporary test key, or unsigned artifact cannot
  pass the gate.
- Missing application/organization identity, dependency verification metadata,
  complete license metadata, or native evidence yields `BLOCKED` for every gate
  that consumes it.
- A gate becomes `FAIL` only after its prerequisites exist and an executed check
  finds a mismatch. Reclassifying absent evidence as `FAIL` does not make the
  release auditable.
- A gate becomes `PASS` only when the evidence names the exact candidate commit,
  artifact hash, tool inputs, and applicable identity without recording secrets.

## Evidence retention

Retain source-controlled policies, matrices, notices, sanitized gate results,
artifact hashes, certificate fingerprints, and relative evidence references.
Do not retain release keys, credential values, raw environment dumps, media,
device captures, absolute paths, or unsanitized logs. Evidence cleanup must stop
active playback/device output where applicable and remove only files created by
the gate run.
