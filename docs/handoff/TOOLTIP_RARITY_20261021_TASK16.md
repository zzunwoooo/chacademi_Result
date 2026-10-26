# Tooltip rarity: bounded client change (2026-10-21)

Base: 7ff347fff2a63aab959e4c1a2ce30fbd8bc7231b, retaining reviewed fixes c6364d95 and NPC integration debb072d. Fresh remote server main was de2e244a; the two newly fetched Story branches were intentionally not merged.

Recognize only full second styled LORE text after legacy formatting removal and outer whitespace stripping: general/high/unique/legendary Korean labels. The full styled lore sequence must match the tooltip body; preserve first-line type, enchantments, attributes, input lore and nested styling. Grade is right-aligned on the existing type row. If there is no safe gap, no type row, or the compact long-name view, retain the grade in the description instead of overlapping/moving the type. Mana-core uses the same existing type row with its original geometry.

Keep panel/orbit textures, .85 scale, normal width 148, title/type positions, fonts, spacing, wand stats, mana-core circuitry, galaxy/orbit and item oscillation unchanged. Extra bounded geometry is behind the upper icon: cyan dust, purple ring/stars, restrained gold halo/rays. General/no-grade uses the existing effect.

Host magic-codex-fabric/gradlew.bat --no-daemon --console=plain --max-workers=2 test build: SUCCESS; 448 tests, zero failures/errors/skips, including 9 rarity regressions and existing styled-text tests.

Deployment recipe: build the integrated client, then replace ONLY ItemTooltipRenderer.class, ManaCoreTooltip.class and add the three TooltipRarity*.class files in the currently published UI JAR. Change only the version in fabric.mod.json. All other ZIP entry contents must match verified public baseline SHA256 22844a5e0190fb0b67665b4201278ca48116e7ed630ab391a1a052fec8360ac6. This prevents deploying unrelated integrated client/server/Story/DB changes or the 12 unapproved gimmick icons.

Verified candidate: 322155586 bytes, SHA256 53d0e0fd6e728da1b78ed14838d5be002b17807beb0da22fab2654367d3c612d. All 18 NPC-related entries and every other unchanged entry match published baseline. ASM verification resolves 260 package method/field references against public baseline + patch. Original galaxy/panel/divider/scroll/localName/warm instruction sequences match public binary.

Full build outputs and logs remain on SSH host under C:/Chacademi/staging/tooltip-rarity-20261021-task16/verification/ and C:/Chacademi/artifacts/tooltip-rarity-20261021-task16/.

No actual game/GPU rendering or live scroll/wand/core interaction was exercised; geometry preservation is established by source review and binary checks. No server restart, settings, DB changes, personal mchdistro launcher changes, new PNG or launcher 0.1.23 publication.
