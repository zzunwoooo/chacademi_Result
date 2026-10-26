Historical review only. Superseded by [final integration](CODEX_INVENTORY_BROOM_RELEASE_20261013.md).

# Inventory integration and separate broom review — 2026-10-12

## Scope and sources

Inventory merges `claude/survival-inventory-20261012` at `868cc12bc7088b526fcbd37398ca00a7198f140e` into integration `d2885eeb04ff830246a77c017877491196551188`. The merge was conflict-free. Existing journal/input, Story descriptions, portrait, pet/shiny, party HUD and server features are retained. Inventory is part of existing `magiccodex`, not a second mod. No unrelated UI redesign.

Broom review uses a separate checkout of `claude/broom-flight-20261012` at `b702858e6eb4a7756ddcd295c7da932e77cf62b7`. Its base is older `c373610`; never replace the latest integration with this checkout. Broom code is not merged into the integration. Section 0 was read before the remaining handoff; section 5 is review only. No server deployment/restart, DB operation, paid API call, or public launcher release.

## Hosting verification

Java 21.0.10, shared hosting Gradle cache, offline builds:

- Inventory: `gradlew.bat -p magic-codex-fabric test remapJar --offline --no-daemon` succeeded. **267 tests, 0 failures/errors/skips**, including 13 inventory layout tests and existing icon eligibility, tooltip color and texture-filter tests. Full Loom compilation includes the access-widener-dependent code.
- Broom: `gradlew.bat :magic-mythic-paper:test :magic-mythic-paper:build --offline --no-daemon` succeeded. **20 tests, 0 failures/errors/skips**. These are existing module tests, not proof of broom networking/physics or live plugin behavior. Motion's independent projects were not rebuilt in this task.
- Existing deprecated Bukkit/Gradle APIs and SnakeYAML version-format warnings remain; no compatibility source fixes were needed.

Inventory artifact under hosting `C:/Chacademi/staging/survival-inventory-20261012-task5/source/magic-codex-fabric/build/libs/`:

`magic-codex-ui-0.39.0-catalog.alpha.2-chat.3-holytaming.1-partyhud.4-taming.1-input.1-journal.1-inventory.1+mc1.21.4.jar`

107210769 bytes; SHA-256 `ee1d105385fbeb9a28784e539ccbdaa437947539c460b0e90b35e297df33233f`.

Broom artifact under hosting `C:/Chacademi/staging/broom-review-20261012-task5/source/magic-mythic-paper/build/libs/`:

`chacademia-magic-mythic-0.1.2-recovery.1-gimmick.1-targets.1-broom.1.jar`

100643 bytes; SHA-256 `2e77228b0f67a959a2869c0a93fbb36e7f4cf791c81185112365cdbd15a83729`. Staged only; not installed on either server.

## Inventory regression scope and limits

Code review confirms the existing `ItemIconMixin` remains the single GUI icon interception point and still skips non-default depth calls. Eligibility/fallback for unsupported/3D icons remains. The new screen calls `DrawContext.drawItem` through that existing route; it does not add another icon interception. Its slot highlight/cursor hooks are restricted to `ChacaInventoryScreen`. Existing tooltip mixins remain. Item, cursor and overlay matrices are pushed/popped separately. Enchanted eligible icons now use the existing high-resolution pass followed by vanilla glint; this affects all GUI callers, not only inventory.

Tests and compilation passed; actual GPU glint silhouette/depth, scaled inventory slots, hotbar/tooltip and third-party renderer interactions still require in-game acceptance. No new successful in-game capture was obtained. Prior hosting client attempts failed during graphics initialization. Do not describe unit/static checks as a live rendering pass.

Armor and offhand are hidden, not disabled: Shift-click can equip armor/shields and F can exchange offhand items. Status-effect list is absent from the new screen. Preserve the handoff's full crafting, recipe book, drag, number-key, creative-switch and resolution acceptance checklist. These are intentional inherited limitations, not silently redesigned here.

## Broom prerequisites and section 0 clarification

Required server: `chacademy-motion-plugin-0.2.0.jar`, plus the new MagicMythic artifact. Required client: `chacademy-motion-0.2.0.jar`, playerAnimator >=2.0.5 and Fabric API for Minecraft 1.21.4. At inspection, school and wild both have Motion plugin **0.1.0**, and the personal launcher has Motion client **0.1.0** and `player-animation-lib-fabric-2.0.5+1.21.4.jar`. Motion was not replaced.

The `flight` spell uses owned broom indefinitely or an unowned practice broom for 200 ticks (10 seconds). A missing/old server Motion API or a player without the broom channel causes the legacy 10-second vanilla-flight route. This is specific to the flight spell. However, a client with Motion installed but missing a hard dependency may fail Fabric startup: the handoff's “any missing dependency automatically falls back” wording is too broad.

Additional source review finding: `BroomService.start` also returns false for blocked worlds, missing `chacademy.broom.use`, cancelled `BroomStartEvent`, vehicle/gliding restrictions, etc.; `BroomLink.flight` collapses false/error to unavailable, and `WorldMech.broomOrFlight` then tries vanilla flight. Thus a broom-specific denial can fall through to vanilla flight, subject to vanilla-flight and existing magic-protection checks. This needs a separate functional decision and tests before deployment; it was not changed under build-only authorization.

Motion restores captured `allowFlight` on dismount, which can conflict with another plugin granting/revoking flight during the ride. MagicMythic `closeAll` does not explicitly stop tracked brooms; Motion's shutdown owns its own sessions. Plugin lifecycle, region entry, two-client synchronization and movement controls need live testing. Persistent mastery uses a new `broom-players.yml`; no such runtime file was created here.

## Section 5 — decisions only, no implementation

1. Proposed blocked spells: `gale_dash` (바람 걸음) and `light_leap` (상승기류). Block only when Motion reports this player is currently riding. Other spells and non-riders are outside this proposal. The list requires user confirmation. `gale_dash` is Bridge `SpellUtility.dash`, setting velocity; `light_leap` routes through Mythic `CHA_light_leap` and `chaselfvelocity` (Y=1.2), followed by slow falling. They do not share one utility implementation.
2. Proposed guard location: inside `ManaBridge.cast`'s `ManaCasting.attempt` callback, after existing `MagicProtectionLink.cast`, before runtime/VFX/command dispatch. Reflection should preserve optional Motion compatibility. Existing internal callbacks return before this position, and HolyTaming has its own transaction path; neither should be accidentally moved or double charged. A new guard must not suppress all spells or bypass region protection.
3. Exact transaction semantics: `ManaCasting.attempt` first checks permission/cooldown, consumes mana, invokes the callback, refunds on false, and starts a haste-adjusted cooldown only on success. The proposal would mean **zero net mana charge and no new cooldown**, but still requires sufficient mana and may briefly publish the deducted state before refund. It is not literally a pre-charge guard. Decide whether that behavior is acceptable or a preflight guard is wanted; no change here. Direct Mythic/admin routes outside ManaBridge would not gain the proposed resource guard.
4. Existing description already says “빗자루에 탑승하여 자유롭게 하늘을 비행합니다. 빗자루가 없다면 10초간 비행할 수 있습니다.” Proposed wording adds **연습용 빗자루로** before “10초간”. This clarifies practice-broom behavior; it does not replace the missing-client fallback explanation.
5. Existing flight defaults: **mana 80, cooldown 30 seconds** (actual cooldown uses MagicHaste). There are **no proposed replacement numbers** in section 5. Successful re-cast to dismount would also pay and set cooldown; re-cast cannot occur while the original cooldown remains. Ground Shift dismount does not itself spend cast mana or start a new cast cooldown, but does not erase an existing cooldown. Decide whether dismount is free, cooldown is changed, or current behavior retained.
6. Boost has no mana API integration or proposed mana amount. Motion defaults: boost cooldown 120 ticks (6 seconds), roll cooldown 60 ticks (3 seconds), catch window 40 ticks (2 seconds), mastery full at 3 hours. These are existing Motion defaults, not approved MagicCodex price changes. Acquisition/ItemsAdder path and balance tuning remain separate decisions.

Source references: `CLAUDE_SURVIVAL_INVENTORY_20261012.md`, broom branch `CLAUDE_BROOM_FLIGHT_20261012.md` sections 0/5, `ManaBridge.java`, `ManaCasting.java`, `SpellUtility.java`, `006_flight.yml`, `BroomLink.java`, `WorldMech.java`, `BroomService.java`, and Motion `fabric.mod.json`.
