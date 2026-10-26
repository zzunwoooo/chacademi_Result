# Final MagicCodex / ChacaNPC integration — 2026-10-05

This supersedes the standalone shop/mailbox and NPC JAR handoffs. Use the four files in the hosting folder below for Helios preparation and parent-coordinated deployment. Do not replace the integrated Bridge/UI with either standalone build.

```
C:\Chacademi\staging\mailbox-20261005-task9\final-ready
```

| Artifact | SHA256 |
| --- | --- |
| magic-codex-bridge-0.30.0-catalog.alpha.1.jar | EE71D2CC93A2A0C51C48D7393DF4C44EEB197265B19B6C2FECA6990647135C76 |
| magic-codex-ui-0.39.0-catalog.alpha.2+mc1.21.4.jar | 131E51DF54E42250C020347A9CB788B1B613C3A9886EB8F874E93EF608FF40EB |
| chaca-npc-0.2.0.jar | 1C83007BE562F8591C26D65004B85F22A5253FAD992A6F5FE91416FE718CE66A |
| Citizens-2.0.37-b3725.jar | 90E2C3C0948F9B54D3196B52C36116651E9512D3656EB4B1B9F110848C3FECA2 |

## Source and verification

- Base: `e2d00d79570b024e3447297ef60a9d1d9fb26533`.
- Shop/mailbox source was committed first, then the exact NPC commit `7f44825295597e80c830cd0c53f0602e066fddc1` was cherry-picked with attribution (`5580d40c6c249cdc088443f291466820c62b9a8a`). No source overwrite or force push was used.
- NPC's one-line `test-portability.patch` uses the packaged 299-spell catalogue instead of an external fixture directory.
- Full hosting tests: Bridge **168/168**, ChacaNPC **12/12**, Fabric **176/176**. No errors, failures or skips.
- Bridge/UI were rebuilt from the combined source. ChacaNPC/Citizens files are the provided final exact-build artifacts, with hashes rechecked; the older AB910 ChacaNPC artifact is not used.
- All **893** original client assets are byte-identical to the latest nickname client. Twelve text asset differences were EOL-only and restored to original packaged bytes. Asset-scoped `.gitattributes` prevents future checkout conversion; no resource contents or original PNG pixels were replaced.
- Nickname, pet, shiny and equipment source preservation was checked against the base. NPC and shop initializers coexist in the combined bootstrap.
- Two approved backgrounds from Downloads are included unchanged. Shop: 1672x941, SHA256 `522476959BB672B94E31075157EE727EA72318D04C4EBB81E89D0B3F5E132653`. Mailbox: 1672x940, SHA256 `082A74423CD6200A96F37494CAED3EB694C90FC0FD00C2BD42396A1671402821`. Source alpha is preserved with white tint and original aspect ratio; no added opacity filter.
- Existing `elena-neutral.png` is used for the shop portrait. Text, items, quantity input, +/- controls and buttons are runtime overlays.

## NPC click coordination

For NPCs claimed by ChacaNPC, the separate shop event hook skips them. ChacaNPC's existing gift-first entry calls the existing Bridge facade; the facade opens an explicitly bound shop and returns `OPENED/BUSY/BLOCKED`, preventing simultaneous shop and AI windows. Without a shop binding, the existing fixed-story/AI path remains. No ChacaNPC implementation or final JAR was rewritten for this route.

## Remaining operational work

- No operational JAR replacement, restart, credential write or live DB migration was performed.
- At final verification, both school/wild MagicCodexBridge folders still lack `database.properties`; their default remains local SQLite. Shared MagicCodex MariaDB configuration and existing-record migration require the DB coordinator before claiming shared persistence in operation.
- The earlier XConomy `SyncData=false` observation is superseded. The DB coordinator reports Redis synchronization enabled and authentication/PING confirmed on both servers at **09:50:43 UTC**, with restart pending. Those settings were not overwritten or re-tested as a running server here.
- ChacaNPC's own schema/configuration, OpenAI key/model access and deployment are parent-coordinated. No paid AI requests were made.
- In-game HUD, inventory/PDC/ItemsAdder round-trip, abrupt crash recovery, live MariaDB contention, NPC gift/story/shop priority and server transfer still require runtime validation. Offline build/tests do not imply these have passed.
- Hosting manifest: `C:\Chacademi\staging\mailbox-20261005-task9\FINAL-VERIFICATION.json`.

The precise shop/mailbox commands, trusted API and conservative external-effect recovery boundaries are in [SHOP-MAILBOX.md](../SHOP-MAILBOX.md).


## Dedicated shop/mailbox DB follow-up

Shop/mailbox now read only `shop-mailbox-database.properties`; other features retain `database.properties`. Missing dedicated settings retain existing local SQLite files, invalid settings fail initialization, and no automatic migration or common-file fallback occurs. School/wild can use the same MariaDB schema via these separate operator-managed settings. Accounts, passwords and operational connection files were not created.

Hosting verification: 168 Bridge + 12 ChacaNPC + 176 Fabric = 356 tests, no failures/errors/skips. Three added tests cover dedicated/common isolation, matching school/wild targets, SQLite preservation and invalid settings. All 893 original client assets and both approved PNGs passed byte comparison. Protected nickname/pet/shiny/equipment sources, VFX and ChacaNPC modules are preserved. Existing final ChacaNPC/Citizens exact-build artifacts remain unchanged.

No deployment, restart, live migration, live MariaDB contention or in-game QA was performed.
