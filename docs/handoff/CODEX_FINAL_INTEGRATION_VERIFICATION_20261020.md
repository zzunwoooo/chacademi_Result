# Final integration verification checkpoint (2026-10-20)

This checkpoint is built and staged, not installed. The operating integration preserves the real 7ec321a operating changes and the previous final branch through 81c7458, selectively adds 0d39e15 Arcana, 9b33f1f visuals, 32ea296 reaction CC, and verified crop child 3b7ba57 (Wildlife 0.4.0). Do not replace operating files wholesale with a feature branch.

## Verified

- Build-Server.ps1 -Verify: exit 0 after resolving the existing Tornado compile classpath and adapting ReactionVfx tests to the new API. Bridge355, Mythic63, Wildlife19, Discovery35, Velocity49, Kyudichi18 tests pass.
- PortableVFX Paper136 tests pass. Native client110 tests:106 pass,4 skipped by existing environment-specific checks. Client build passes.
- Real ClaudeConfigLoader:279 packs /1237 systems,3958 admitted assets,0 errors,1 pre-existing DyeRinse world-space orbital warning. Encoded367205944 bytes; BC7 texture485536580 bytes. Cache cap512MiB, GPU cap1024MiB unchanged, resident/prewarm1237 systems.
-37 telegraph physical scale cases preserve their exact desired factors. Generic Claude magnification remains1; the existing physical scaleInput API and authored scale descriptors are used. Four packs missing descriptors receive derived descriptors; GravitySlam bounds include its existing requested factor. Original archives and original/derived hashes are retained in manifests.
- Four authored exports exceed standard16 systems/64 layers. Folder splitting changes public effect namespaces; splitting layers changes shared RNG ordering and link/stop sequencing. No systems/layers/visual content are removed. AuditedPackLimits admits only exact canonical SHA256 exports: ElementReactions19systems, CinderSkipring101layers, FinalKnot68layers, NightChain70layers. Edited or renamed content retains standard16/64 limits. Particle/frame/GPU/cache bounds are untouched. Per-export fixtures test exact preservation, loader selector envelopes, and refusal to inherit exceptions after mutation.
-30 systems simulated at60Hz (looping systems10seconds), no particle-budget exception. Peak live1074; highest per-system CPU-step p95=397.4us, maximum sampled step1566.8us on this host. This is CPU-only evidence, not an FPS/GPU/real-game claim.

## Preserved decisions and gaps

- Fourteen held Arcana mechanics stay untouched.175 drowsiness remains disabled;212 cinder_skipring damage base/ratio remain0. Equipped passives177/193 retained. Six new display numbers share only their exact disabled legacy pairs; existing IDs/numbers300-323 and old wind numbers remain unchanged.
- Arcana33 discovery learning conditions are absent upstream; none invented. Six new donation prices are absent; none invented. ShadowTracking original ZIP has no runtime JSON and is excluded; held187 stays inactive.
- Reactions use approved CC60ticks/hardCC20ticks and size factors. Flash preserves the old aggro drop. Body-edge hit radius and artifact redesign remain undecided; center-radius gameplay is preserved.
- aa785a5 did not reverse6504a31 all-vanilla-bossbar hiding or64-block boss priority; it centers16 status images and adds hover name/effect/time popups. Later operating integration adds fresh shoulder-camera aim handling without changing that boss selection policy.
- Crop awakening:14bbmodels +2Mythic YAML; Wildlife0.4.0; player cap3 includes same-tick reservations,chunk cap6,area cap24 and existing habitats/species preserved. Chance0.05 unchanged. Do not run the production harvest QA script which writes backups/forces chance1.0.

## Deployment requirements still pending

- Final icon connection task is preparing9 additional icons (held mechanics remain held);6 approved generated icons already have client/server ID-to-PNG links and exact transfer hashes.
- Fabric final tests/build and complete final launcher packaging must pass after the last icon links. Launcher must also deliver the new portable-vfx-client3.2.1 audited runtime, not only UI/assets/limits.
- No live JAR replacement or restart performed at this checkpoint. Final deployment must compare each before/after hash and preserve wild Multiverse worlds.yml SHA37768d1a53a6fe2ad01f322f2621f7df0c74cc71099dab715976cb2ec124171d.
- All3 currently run the same MagicMythic plugin, so school/wild/arena receive the coherent CC JAR. Wildlife+crop definitions/models are wild-only. Velocity party and arena Kyudichi are included.
- ModelEngine auto-generates its pack after server startup. ItemsAdder merges ModelEngine/resource pack; school self-host8163,wild8162,arena uses school8163. After wild models compile, regenerate ItemsAdder's hosted pack, inspect crop model/texture references, verify the hosted generated.zip hash and logs, then ensure clients receive that pack. Do not substitute raw bbmodel transfer for native generation.
- Coordinate the final restart and normal user game exit for locked personal mods. No forced game closure, real-game capture, map copy, additional backup or nickname DB changes. Do not expose secrets.
## Exact audited export identities

| Export | Canonical SHA256 | Allowed systems/layers |
|---|---|---|
|elementreactions|`4aaa77707430d5e69025e59676a36bb525b4cec48410f578ddb7632ab237348b`|19/64|
|cinderskipring|`984399ad237fb5e0c97d6946b4bba5a96a3e681188895d58ed0d3181cc7c0ae4`|16/101|
|finalknot|`804a0a81815303ef95ec06f22f58531d1ada1ef2d93f94e340574a97a904519d`|16/68|
|nightchain|`33f0409df5704e89030d31cde3908e39ab4a6a369e4577e8862ae5b24ce7df0f`|16/70|

AuditedPackLimitsTest: both tests pass; every original system and layer count retained, selected-envelope parse succeeds; renamed or gravity-edited content rejects the exception and retains16/64. Final Fabric408 tests pass. Server plan4367 files/15 JAR replacements; target summary is recorded separately in final-target-change-summary.json. No live installation has occurred.

## Corrective verification: inherited crop mappings and admin VFX commands

Reproduced JavaPlugin's YamlConfiguration defaults relationship with an existing file lacking crop-awakening and copyDefaults=false: effective enabled=true/chance0.05, but getConfigurationSection(...).getKeys(false) produced zero crop IDs. The defect is map enumeration, not the scalar probability fallback. CropAwakening now reads inherited crop mappings only when the direct path is absent; explicitly empty or custom mappings remain authoritative. Before-fix regression failed with [], after-fix resolves all7 IDs. Wildlife21 tests pass; version0.4.1-integrated-20261020.1-defaults.1. Staged wild config explicitly adds only the approved crop section; all previous spawn settings remain unchanged (chunk6/area24). Live config is unchanged.

All11 MagicMythic effect/request call sites were audited. ReactionVfx.effect converts every nonunit Claude factor via the installed authored scale descriptor reference to the existing physical startCast input, always with a unit transform. Missing/unsafe/invalid descriptors and factors outside authored bounds are rejected; pack references are cached until reload. Particle/frame/GPU guards are unchanged. Actual seal, frost_laser and fx command regression captures physical7m/24m/8m inputs, unchanged effect IDs and unit transforms through PortableVfxService.startCast. Generic fx also previously parsed its pack/system as seconds; its own parameter parser now handles that branch. Descriptor rejection and reload-cache tests pass. MagicMythic66 tests pass; version0.2.0-integrated-20261019.3-ccvfx.1.

Build-Server.ps1 -Verify and PortableVFX Paper build both exit0. Corrective evidence is corrective-defaults-and-command-evidence.json; exact caller inventory is vfx-effect-caller-audit.json. No installed file, service or game state changed. The denied7-icon/2-patch transfer has not been retried and final icon packaging remains blocked pending explicit authorization.

## Final22 icon delta verified

All22 PNGs are present; prior15 are preserved. New7 originals and2delta patches transferred after exact approval and verified by size/SHA256. Six approved YAML icons and matching Catalog/Discovery icons change only artwork;171 has only image/loader mapping. Loader preserves custom paths, held9 are not registered. Fabric full build, PortableVFX Paper and Discovery build pass. No fishing changes have been integrated in this checkpoint; fishing is now separately approved for the final combined deployment. No operating replacement/restart has occurred.
