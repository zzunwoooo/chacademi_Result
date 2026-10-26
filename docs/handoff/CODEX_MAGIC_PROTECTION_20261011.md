# Magic protection candidate — NOT DEPLOYED

Independent source: C:/Chacademi/staging/magic-protection-20261011-task5/source. Based on chat/discovery preparation plus Claude magic d4c0859. Dungeon models are excluded. Neither original staging nor operating files were edited. No restart, launcher replacement, commit or push.

## Region flag
WorldGuard7 custom StateFlag `chacademi-magic`, default allow, registered during plugin onLoad before WorldGuard locks its registry. WorldGuard and MythicMobs are hard dependencies. Any pre-existing flag of this name (even StateFlag) is treated as an ownership conflict: addon stays unavailable and Bridge refuses magic. Missing/disabled addon, missing WorldGuard, query/linkage error also deny. No OP or WorldGuard session bypass is consulted. Queries are location-only with no player association; use the ordinary all-player region flag, not membership-group exceptions. Normal WorldGuard priorities/overlaps apply.

Commands (documentation only, not executed; use actual world name):
```
/rg flag -w <world> <region> chacademi-magic deny
/rg flag -w <world> <region> chacademi-magic allow
/rg flag -w <world> __global__ chacademi-magic deny
```
Remove the flag with `/rg flag -w <world> <region> chacademi-magic` to return to inherited/default behavior. A higher-priority allowed region can override a lower-priority deny according to normal WorldGuard state resolution.

## Enforcement
ManaBridge checks before gameplay callback including wind_message; runtimeCast also checks before prepare/VFX. Prepare uses the same addon enemy/pet policy as execution. Target/aim location checked before VFX. CatalogGameplayFlow preserves no-VFX refusal and no refund after a committed VFX even when execute returns false or throws RuntimeException/LinkageError. Material checks that inherently remain in utility execution still follow that committed-cost rule.

Target rules exclude Citizens NPC metadata, cosmetic pet tags, active MCPets via the existing getFromEntity API, tamed entities, owned Mythic mobs, CHA_/GM_ prefixes and armor stands. MCPets hook is cached by plugin classloader; lookup failure denies targeting. Positive support remains caster/nearby playable players, not silently narrowed to a party. Harmful/unknown potion effects must pass enemy/PvP policy and protection checks; default pvp-worlds=[] blocks use on other players. Self use remains allowed outside denied regions.

Execute rechecks caster and target locations. Bolts check each bounded substep; zones each pulse/target; marks, guards, flight, platforms and timed block utilities recheck active objects. Native delayed potion/velocity lines now use guarded chaselfpotion/chaselfvelocity mechanics, with generator and produced YAML updated together. Existing independent high-resolution chat/UI and discovery definitions are preserved.

WorldGuard PvP deny on both endpoints and invincibility allow on player targets are checked directly without OP bypass. Real damage goes through Bukkit damage and its final cancellation result; no setHealth(0) execution fallback. Cancelled damage skips follow-up effects. Zero-damage harmful effects use a cancellable MAGIC damage permission probe (no health mutation), preserving plugin cancellation. Actual runtime plugin interaction still needs in-game validation. Native Bukkit invulnerability/armor may reduce execution damage; that is intentional rather than forcing death.

WorldGuard spatial RegionQuery cache is reused by its container; no custom long-lived region decision cache, no all-region/all-entity scans. Ally selection uses bounded nearby entities, not all players in a world.

## API/build and limits
Build uses hosting Java21 with actual existing MythicMobs5.9.0, WorldGuard7.0.13 and FAWE/WorldEdit API. Paid dependencies remain local compile/test inputs and are not shipped in review bundles. Typed Mythic getDouble/getEntityTargets replace the speculative reflection fallback.

Unit coverage includes actual WorldGuard flag registry conflict and RegionResultSet deny, fake-world inside/outside/moving-target checks with forbidden OP-bypass calls, WG PvP/invincibility/failure, cancelled damage with no forced setHealth, shared pet/NPC/summon policy, harmful potion support routing, and four VFX/cost-boundary cases. This is NOT an in-game multi-plugin test.

Pending live checks: flag availability after startup, priority/group behavior on real configured regions, OP inside/outside casts, projectile/zone boundary, Citizens/MCPets instances, cancelled execution with WorldGuard and guards, poison in PvP-disabled areas, protected block effects, Mythic skill registration/reload and delayed casts. The original addon registers mechanics in onEnable after its dependencies; verify Mythic initial skill load order and reload skills after the addon is ready as the Claude handoff requires.

Known remaining defects outside this request: TempBlocks still places blocks before a best-effort journal save, and unloaded-world recovery can discard records; do not claim crash-safe temporary platforms. Existing prison release restores awareness=true instead of original awareness. Existing spell balance/ranges are provisional; Claude changed four prior route ranges. The 95 VFX/gameplay set is not the discovery97 set. No dungeon signal/model integration was added. Bridge candidate contains the earlier discovery/donation changes, including automatic policy-table initialization on a future deployment; deployment/DB approval remains separate.
