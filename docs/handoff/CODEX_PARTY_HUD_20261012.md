# Party HUD integration and deployment - 2026-10-12

Merged Claude party HUD branch 0dcd626835dd18f93abedd64677992a702a78402 into current 9f6087bb4f15ff3706cc39da4b18f88f9205162d. The older branch index is historical; this handoff supersedes it.

Party HUD shows house icon, Korean nickname, health/mana bars, offline state and hover values. Alt enables moving/resizing; right click resets. /hud 파티 commands are localized in the source. Data flows from the current trusted Paper backend through Velocity to Fabric; spoofed/stale backend values are rejected. Large parties at maximum zoom now fit within the screen. Existing Chat Layout 1.5.1 target lambda$initialize$7(DrawContext, RenderTickCounter) is preserved and confirmed with javap.

HolyTaming rainbow_collar condition text is now: 몬스터 교화 실습 수행하기. Existing manual user-management condition grant remains; no quest or automatic objective counting was added. Mana 150 and cooldown 60 are unchanged. Unmodified old bundled catalog files are backed up and migrated; custom catalogs stay untouched.

Hosting Java 21 verification: 597 tests passed, no failures/errors/skips (Paper 291, discovery 34, Velocity 35, Fabric 237). Paper/Velocity builds and Fabric remapJar succeeded. Source migration, protocol golden bytes, trusted connection rejection, icons and screen fitting are covered. Actual multi-account gameplay, cross-server HUD refresh and Alt/chat overlap remain unverified.

Deployment: fresh school/wild player queries both returned zero, then normal stop and restart of school/wild/Velocity. Changed Bridge, discovery and Velocity JARs backed up and replaced. Both Paper servers reached Done and all three TCP ports responded. Existing runtime configs remain except the single rainbow_collar condition text. Personal client replaced after no java/javaw process was found; UI hash verified and private Story font/ChatPlus/Chat Layout hashes preserved. Public launcher v0.1.4 is published: https://github.com/zzunwoooo/chacademi-launcher/releases/tag/v0.1.4 . Launcher commit 3f229d36e9e4947eae16e386d663a23e54d68419; game build source 1c7cf990f08e587fbf434daeb8f64c1665af148f. All eight uploaded asset digests match; public distribution/latest.yml/SHA256SUMS downloads and main feed verified. Launcher tests: 8 passed; packaged ASAR and updater metadata checks passed.

Shared donation DB migration, pending crimson_heart/bouncing_droplet, dungeon 63-model assets and gimmick module remain outside this change. Existing SkeletalKnight warning and wild ItemsAdder generated.zip absence remain separate issues. No paid API calls, credentials, private fonts/images or server JARs are published.

Usage: create/accept a party, then use /hud 파티 localized commands (on/off/reset), hold Alt to move or resize, and hover bars to inspect values. Manual HolyTaming grant: /유저관리 <닉네임> 마법 조건지급 rainbow_collar.

UI archive preservation audit: 918 existing assets byte-identical, only two HolyTaming description YAML files changed, and seven party HUD PNGs added. Installer SHA256: af757a3a4fea5a60d0383e395afb03128cf3f3eb2e96e4a1ebcfd0b23228fe3e.
