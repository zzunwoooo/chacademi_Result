# Approved mob spell icons v2 — 2026-10-20

Base: ad2e087. Only magic-codex-fabric changes plus this documentation. Runtime300..323 map to planning215..238; gameplay IDs and ordering unchanged. Exact approved v2 ZIP SHA256: 3ec8745c5baa9edee8ae30df487544e76efb641388b7d59fff4262756482321d. Rejected v1 was not used.

24 original RGBA PNGs added with exact per-file hashes in MOB_ICONS24_V2_20261020_MANIFEST.json. Default YAML icon fields only changed, with old/new hashes accepted for default migration. MobSpellIcons resolves blank/known former paths to the approved assets for local and synced display records and discovery notifications. Custom paths and unrelated spells sharing old textures are preserved. No server resource or mechanic change, server deployment or restart required.

Fabric414 tests pass. Final292303047-byte UI JAR SHA256: 384ae4f5ad95922c8282dc8cbc2227982b4f3aa7eff465df458faf136e49f7fb. All24 embedded PNG hashes verified; existing22 icons, fishing resources and2831 old resources byte-identical. HUD, Arcana, reactions, crops, fishing, Kyudichi retained. UI version0.40.1-integrated-20261020.2-mobicons24v2.1+mc1.21.4. Launcher v0.1.17 packaging uses only this UI update;84 other managed modules unchanged.

Evidence: remote C:/Chacademi/artifacts/mob-icons24-v2-20261020/. Packaging and installation outcomes are recorded there after completion. No backup, map copy, forced game termination or gameplay capture.
## Deployment complete
- Public v0.1.17: https://github.com/zzunwoooo/chacademi-launcher/releases/tag/v0.1.17 ; launcher main ad41dbbe68ad57a8f16c147f529b1ef322afeacb. Eight uploaded asset sizes/digests, public latest.yml SHA512, main distribution HTTP and UI URL verified. NSIS and portable ASAR manifests match source. Only UI changed;84 modules preserved.
- Personal launcher UI installed2026-10-19T23:27:22Z;292303047bytes;SHA256384ae4f5ad95922c8282dc8cbc2227982b4f3aa7eff465df458faf136e49f7fb. All24 embedded PNGs match v2 original hashes. Other14 mods including PortableVFX, options and VFX settings unchanged.
- All15 existing server JAR hashes and all4 service PIDs match the pre-icon deployment. No server file replacement or restart performed. Source ad2e087 gameplay preserved; existing22 artwork and2831 old JAR resources unchanged.
- No rejected v1, backups, map copy, forced game termination or gameplay capture. Actual in-game rendering remains manual QA; this work verified code paths, unit tests, JAR resources, installer manifests and installation hashes.
- Evidence: C:/Chacademi/artifacts/mob-icons24-v2-20261020/deployment-final-result.json plus ui-build-evidence.json/import-proof.json/fabric-build.log. Launcher evidence in C:/Chacademi/staging/temp-launcher-mob-icons24-v2-20261020/publication.json and public-http-verification.json. Personal evidence on PC: personal-mob24v2-installed.json.
