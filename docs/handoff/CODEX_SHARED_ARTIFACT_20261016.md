# Shared artifact ownership — operating integration, 2026-10-16

Base: operating integration 807b040c74681e162603338ae0e3b8a729fdfb9e. This change uses the existing player-state MariaDB credentials for artifact-only tables. It does not move LuckPerms, change OP membership, broaden credentials/security, modify the summon pool, enable held artifacts, change player-state equipment storage, update a client protocol, or modify the temporary launcher/feed.

## Authority and compatibility

Real ownership is stored in codex_artifact_ownership. Both servers refresh online ownership snapshots every 40 ticks and reload them for a new connection. Unknown/unavailable shared state refuses new purchases; there is no ordinary-player fallback to old local LP ownership permissions. Grant/revoke/reset use the same per-player SQL transaction lock as acquisition. A revoke is retained as a false row; source migration markers prevent old LP data from being imported again.

Existing OP temporary access remains server-local and uses the previous effective artifact permission checks only when that player is currently OP. It is not written to ownership rows. The existing test-owns-all setting is preserved. OP-only summon results remain duplicates, as before, and do not create permanent ownership. Explicit shared revoke/reset removes real ownership but does not remove the separate OP temporary access. OP status differing between servers can therefore still produce different administrator access; this is intentionally separate from acquired ownership. No OP grant/revoke or permission migration occurred.

Raw artifact_equipped values remain in the existing shared PlayerState table/PDC and are never automatically erased by ownership refresh. Effective equipment filters inaccessible IDs. Explicit player equip/unequip continues using the existing PlayerState lease/save path. This preserves operator-origin equipment when moving to a server where that account is not OP.

## Administrative commands

The existing Korean artifact-management command remains. An ASCII alias is added:

```
artifactadmin grant <online-name|uuid> <artifact-id>
artifactadmin revoke <online-name|uuid> <artifact-id>
artifactadmin reset <online-name|uuid>
artifactadmin check <online-name>
```

The existing magiccodex.artifact.admin permission protects these actions. Defined but held/disabled artifact IDs may receive explicit grants without changing their enabled status or the summon pool. Local `lp user ... permission set/unset magiccodex.artifact.own.*` is no longer the real-ownership interface; use the shared commands. Alchemy discovery continues using its existing LP path and is outside this migration.

## Atomic acquisition and failure handling

Acquisition uses an operation UUID and a transaction locking codex_artifact_accounts for that player. New/duplicate classification, ownership, result, and duplicate-shard outbox rows commit together. Retrying the same operation returns its original result without granting or mailing again, including after a later revoke. Simultaneous school/wild acquisitions of the same artifact produce one new ownership and the appropriate duplicates.

Paid requests are retained in private plugin-data artifact-purchase-receipts files until the SQL transaction is confirmed. DB failure does not reroll or charge again; recovery replays the exact receipt. Duplicate mail uses the existing shared MariaDB mailbox and a unique source key `artifact-shared:<operation>:<index>`. Failed mail remains pending until acknowledged. No player identity lists are logged or committed.

A process/disk failure exactly between inventory debit and the receipt's paid marker is inherently ambiguous across Minecraft inventory and filesystem persistence. A prepared-only receipt is quarantined and disables this service on startup for operator reconciliation; it is never silently promoted, discarded, or blindly refunded. Do not delete private receipts without checking the player's inventory, operation row, and delivery state. This limitation is distinct from the tested DB outage recovery path.

## Migration and preservation

The artifact-scoped audit uses LuckPerms API searchAll with only the ownership prefix and relevant ancestor wildcard keys, for users and groups. It writes a private TSV containing only relevant nodes. It does not export all LuckPerms data. The audit script is disabled and removed after use. A full LP export was blocked by automatic approval review and was never performed.

Both initial audits contained zero explicit user/group ownership nodes, including zero negative, expiring, contextual, or wildcard grants. Existing equipped records belonged to three operators: two OP on both servers, one OP on only one. Those operator records were backed up separately and are not treated as permanent acquisition.

Before deployment, repeat the scoped audit while both servers have zero players, compare it to the reviewed snapshot, then stop both servers gracefully. The CLI validates both files before writing. It accepts only unconditional permanent direct-user ownership; group, wildcard, negative, expiry or context nodes stop migration for explicit semantic review. It imports the union with insert-if-absent and records school-v1/wild-v1 markers and exact source checksums. Replay cannot resurrect revoked ownership and rejects a changed source checksum.

```
java -cp <bridge.jar>;<mariadb-driver.jar> school.magiccodex.paper.ArtifactOwnershipMigration <existing-player-state-settings> <private-school.tsv> <private-wild.tsv>
```

No real settings file, private TSV, player records, log archive, or credentials belong in Git. Private backups are under C:/Chacademi/backups/artifact-shared-20261016-task10. The public deployment receipt contains only counts/hashes.

## Verification

Run on the host with Java 21:

```
./gradlew.bat --no-daemon --console=plain --max-workers=2 verifyServer assembleServer
```

New tests cover two-store/concurrent acquisition, five-pull duplicates, reconnection, idempotent retry, explicit grant/revoke/reset, revoke versus old operation replay, one-time union migration, rejecting changed/conditional inputs, mail acknowledgement, unavailable DB preserving ownership, paid receipt replay, uncertain-debit quarantine, stale local permissions, original equipment preservation, and OP temporary access without permanent grants. Store transaction tests use isolated H2 in MySQL mode; real-player/game UI/flight tests are not claimed. The existing Mythic test dependency was updated from the removed old VFX filename to the already deployed carrier.1 filename; Mythic production code is unchanged.

Client protocol and personal mod files remain unchanged. Existing alchemy, 101–167, reaction VFX, BC7, unit ring, 103/134, and realtime season epoch 2026-10-16T15:33:16+09:00 are retained.

## Applied deployment

Both servers run Bridge 0.31.0-expansion71.1-artifact.1-summon.2-effects.2-multi.1-alchemy.1-shared.1. SHA256: 4993b1afee26466eb1a672cb41cbf6bb7352fc8b4bc9303d8ba2b1c56d7d2dfe. Both reached Done and logged shared ownership ready with both migration markers. VFX remains 157 bindings / 0 skipped, startup errors zero. The 110 other manifest files are unchanged, including artifacts definitions, held states, spell bindings, and the realtime season configuration.

Final source audits matched the initial zero-node backups. MariaDB migration imported zero ownership rows, committed both source markers, and a second run reported both sources already applied. Existing equipment rows (3) have the identical pre/post digest c0e4098e2b27f6e240dc78fce2be18630b42744a88713026e7cd425f55374b04. Scoped audit scripts were disabled and removed. Five artifact tables were confirmed InnoDB. No player identity list was published.

Final server verification: 588 tests, zero failures/errors/skips, including 17 new regression tests. Client protocol and personal UI were not changed; the personal Minecraft process check was empty. School/wild/Velocity network pings succeeded. Real-player login, server transfer, crafting/summon animation and operator UI rendering were not executed; transactional sharing and failure paths were verified in automated tests and service startup against the real shared database.
