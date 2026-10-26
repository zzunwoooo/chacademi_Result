# Opening-final host verification — 2026-10-25

Requested input `e0b8d778`; remote had advanced to `1663065ecd1cda79d818a6d27b1f6a7504cbc09e` (one review-document line only; no code difference). Verified source fix commit `62489c281beaa176085c953d11a9a2640ac31fb3` on `claude/opening-final-20261025`. Existing live handoff worktree/branch was preserved.

## Actual builds and complete repository test inventory

JDK 21.0.10, existing host Gradle wrapper 8.12.1 and Gradle cache, offline Paper 1.21.4/Fabric Loom 1.10.5. `tools/Verify-OpeningFinal.ps1` ran all nine build groups with exit 0: root verifyServer+assembleServer (all 13 server modules), MagicCodex Fabric, Story plugin, Story Fabric, PortableVFX common/paper/client, Motion plugin/mod, chat layout, NPC model. This is actual compilation, remapping and tests, not syntax-only checking. Final invocation:

```powershell
& 'C:/Program Files/PowerShell/7/pwsh.exe' -NoProfile -File tools/Verify-OpeningFinal.ps1 -LogDirectory C:/Chacademi/artifacts/opening-final-20261025-task3/final-verification
```

JUnit: **2260 total, 2251 passed, 9 skipped, 0 failed/errors**. Module counts and exact skipped test names: [source-test-results.json](opening-final-20261025/source-test-results.json). Existing optional fixture/in-engine tests remain skipped; none were newly disabled. VFX common intentionally disables its JUnit task and runs its dependency-free protocolTest main instead: readiness 2125 and protocol 31753 assertions passed. The independent reference discovery editor `python test_server.py` also passed all 13 tests using temporary data and loopback only. No manual in-game rendering or live plugin boot was performed.

## Failures resolved

The fresh worktree initially lacked ignored host dependencies (SQLite/MariaDB, HuskSync/Redis, MCPets and local tornado APIs). Hash-matched existing host copies were restored privately, with no binary/credential upload. The tracked preflight inventory is `build-support/opening-final-dependencies.json`; restore matching dependencies before rerunning. Initial missing-driver/compile errors are environment setup failures, not silently ignored tests.

Four stale Bridge test cases then failed. CatalogStartupRuntimeTest did not isolate newly introduced SpellSounds and static command registration as existing unrelated service boundaries. Its original runtime/cast/protection assertions were retained. JournalBridgeTest still expected the retired client-owned story catalog/ACK editing contract. It now verifies server-owned story catalog, forged catalog/ACK rejection, operator stage-command guidance, exact journal permission, server rows and unchanged quest/dialogue owner routing. This expands one case to three. Only those two test sources changed; no production behavior or test expectations were weakened to conceal a product failure.

## Source build versus live-preserving candidate

[Raw source artifact hashes](opening-final-20261025/raw-source-artifacts.json) identify the ten requested artifact types. **Raw JARs are not approved live replacements.** The live binary-baseline dependency and VFX cache source gap described in [LIVE_SOURCE_HANDOFF](LIVE_SOURCE_HANDOFF_20261025.md) remain. Private candidates must retain deployed methods/classes/assets and combine only the new source delta. Passing source tests does not prove the live hotfix/cache implementation is reproduced by a clean Gradle build.

Private build/test evidence root: `C:/Chacademi/artifacts/opening-final-20261025-task3`. Candidate and configuration patch status will be recorded separately before any deployment approval request. In particular live ch1-17 is already an alchemy dialogue, so sorting-dialogue renumbering must preserve that occupied target rather than overwrite it. Live school ch1-11 NPC12 binding must remain.

## Authorization boundary

No operating file, process, public launcher feed/release or personal launcher was changed. Dungeon remains deferred; nonexistent tutorial servers are not installed. The previously cancelled harbor-cutscene ZIP remains cancelled and was not resumed. Deploy/restart/public launcher replacement require the owner's additional approval after this report.

## Private configuration preparation

42 staged files, no operating writes. [Shareable patch specification](opening-final-20261025/config-patch-spec.json) contains paths and pre/post hashes, not operating file bodies/commands/credentials. Private ledger SHA256 `3f87b22f5b5f647fcd5fddb6f3b7da67121bcb82fee1fd184f7e1a8004138c10`, payload under `config-stage/payload`.

Existing alchemy `ch1-17` is preserved as `ch1-17-alchemy-preserved`; only its ch1-20 reference changes. Sorting 13-dialogue family becomes ch1-17, and its completion pattern and server_command key move together. Exactly two step fields differ; NPC12 and all other step values are equal. Virtual resulting catalog: 39 IDs, no duplicate or unresolved stage/command links. Legacy sorting files are only listed for archival after backup on a later approved installation, not moved now.

The nine Discovery/VFX settings candidates change only guiding_star manual condition and 26 icon fields (87,88,300–323). All 26 PNG paths resolve in source and current temporary client, byte-identical; generated server packs do not override them.

## Existing skipped tests

- 1 Story content-path test: `CHACA_STORY_CONTENT_ROOT` fixture not configured.
- 2 FishTank MariaDB integration tests: `CHACA_FISH_TEST_DB_CONFIG` isolated MariaDB configuration not supplied; no production DB write tests were attempted.
- 2 pet definition/connection audits: `CHACA_PET_REVIEW_DIR` prepared review directory not configured.
- 3 Claude model fixture/cache tests: `CLAUDE_MODEL_FIXTURE_DIR` not configured.
- 1 HolyTaming authored-pack fixture: `PVFX_HOLYTAMING_DIR` not configured.

These are nine existing optional test cases, not nine build failures. Live/manual visual, actual player cross-server travel and graphics/GPU rendering remain unverified.

## Actual candidate checks

The live-preserving school Bridge candidate SHA `54a5da6842d7870f99eeec2654bad398f3cd380a0ee39bb6e5312b1bd101240b` passed 23 focused runtime tests with source main classes excluded: startup/runtime cast, server-owned journal, operator helper, sounds, title awards and title protocol. [Exact result](opening-final-20261025/actual-bridge-candidate-tests.json).

Actual server/client Title protocol classes are byte-identical. Journal/HousingRank class bytes differ after Fabric remapping metadata, but all fields/descriptors/method instructions compare equal: 3 Journal,5 Title,10 HousingRank classes. [Semantic comparison](opening-final-20261025/actual-protocol-semantic-comparison.json), [raw byte evidence](opening-final-20261025/actual-protocol-class-comparison.json). This distinguishes metadata differences from wire incompatibility.

## Final private candidate bundle

All 17 requested per-target JARs are fixed under `C:/Chacademi/artifacts/opening-final-20261025-task3/stage/jars`. [Manifest](opening-final-20261025/private-stage-manifest.json), SHA256 `948bf8dbd81f6e78eb2f4d2f18921f9d4b78e3f91c3995fcb4a67329a1f31b01`, identifies 14 server JARs and 3 temporary-client JARs. Configuration payload is the separate 42-file ledger above. No public feed/download or installed mod was updated.

Final actual-JAR validation: 17 SHA checks passed; removed entries 0; 13260 untouched entries byte-identical; 183 changed classes passed JVM verification with external missing 0 and failure 0. [Validation](opening-final-20261025/FINAL-VALIDATION.json). Discovery and Story mod have no class delta; resources/metadata are preserved/merged. Existing Mythic mechanic registration and Meteor four classes are preserved; new Quidditch/Kyudichi registration smoke passed. Housing's 90 existing Store methods are instruction-identical. Bridge lifecycle ordering and VFX renderer/fingerprint/GPU lifetime implementation stay operating-baseline based. Narrow inserted changes strip back to the operating instruction stream in the recorded proofs.

The private bundle incorporates live-preserving binary merges beyond source tests. Source deltas and bounded merge tools are tracked under `tools/opening-final-20261025`; input baselines are still necessary. The archive is not claimed to make the VFX cache implementation source-complete.

**Remaining gate:** owner approval for operating installation and restart, including the occupied ch1-17 preservation-alias plan. At that time recheck every operating prehash and back up narrowly before applying the whole bundle. After deployment, actual boot/plugin registration/logs and in-game story, housing, sound and VFX behavior still need validation. No current compile/test/link blocker remains; there is no permission to deploy in this task.
