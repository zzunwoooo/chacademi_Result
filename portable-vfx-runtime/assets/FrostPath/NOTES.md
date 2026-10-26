# FrostPath (서리 길, 중급 340, id frost_path, 물/얼음) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| frost_path_begin | static, phase begin | cast (t = 0) | P = caster feet, M = caster horizontal facing (+Z forward). Stomp + cast circle 0–0.2 s; frost wave races z 0.4 → 8.4 m in 0.08–0.50 s; road segment k (1 m, centre z = 0.9 + k) crystallizes at t = 0.106 + 0.0525·k s; end-cap burst at 0.50 s |
| frost_path | static, phase sustain | t = sequence.sustainStart = 0.6 s | runtime `holdDuration` = 5 − 0.6 = 4.4 (default) → road visible 5 s. loop, duration 60 (> hold.max 12) so its t = 0 bursts happen once |
| frost_path_end | static, phase end | hold expiry (or cancel → jumpToEnd) | road breaks from the caster outward (segment k at 0.12 + 0.055·k s), shards / vapour / droplets, gone by ≈ 1.1 s |
| frost_path_skate | attached (caster) | the mod, while the caster stands on the road (speed buff on) | loop; stop it (stop emitting) when the caster leaves the road or the road ends. world-space skid trails (rateOverDistance) |
| frost_path_chill | attached (enemy) | the mod, when the 3 s slow is applied to an enemy on the road | not looped, duration 3.0 s (= slow duration). Re-applying the slow = start a new instance |

Road area (gameplay range): local x ∈ [−1.0, 1.0], z ∈ [0.4, 8.4] from the caster's feet (2 m wide, 8 m long). The white edge lines (`fpRims`, S04) are exactly at x = ±1.0 and the far end at z = 8.4 = the true road range. If the server's road is wider/longer, change HW / ZA / ZB at the top of the spec.
Slow / speed icons are the game's own; the VFX only shows the spell.

## Held layers
Road (ice body, dark cracks, glowing cracks, rims, rune strips, edge crystals) + cast circle: ONE particle each in `frost_path` (life 59 s, `onStop` tail 1.1 s).
Begin per-segment copies (G0..G7) live until 0.72 s and fade over 0.6–0.72 s while the held road fades in at 0.6 s (lock-in overlap, no gap).
Cast circle draw-on copies start at t_s with rot3 = ω·t_s, sustain copies at 0.6 s with rot3 = ω·0.6 and the same rotLife.
End per-segment melt copies (M0..M7) start at end t = 0 while the held ice body fades out in its first 0.03 s of tail.

## Features
Only client-supported features (client_compat check OK, `fix` = 0 changes). High impact: meshParticle (road strips, rims, runes, crystals, wave crest, circle), bloom,
orbitalVelocity (skate leg wind), attachedFollow (skate, chill), phasedSequence + stopTail (held layers), worldSpace + rateOverDistance (skate trails).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
