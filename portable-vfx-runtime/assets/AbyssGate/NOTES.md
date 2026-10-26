# AbyssGate (심연의 문, 궁극 371, 어둠) — converter notes

## Systems and runtime signals (gate P = aim point on the ground, M = caster horizontal facing; the gate front (local −Z) faces the caster)
| system | role | started by | notes |
|---|---|---|---|
| chant | attached (caster) | cast t = 0 | 3 s chant: foot circle, ink/violet cocoon, orbs → hand core, release flash at 2.88 s. Cancel = stop (onStop tails 0.2–0.3 s) |
| mark | static (aim point) | cast t = 0 | true range ring r 8 + faint runes + seam of the gate-to-be. loop; **stop it at chant end (3.0 s) or on cancel** |
| begin | static, phase begin | chant end (gate cast t = 0) | circle locks in, gate rises 0 … 0.8 s (thud 0.78), doors open 0.85 … 1.4 s |
| sustain | static, phase sustain | t = sequence.sustainStart = 1.4 s | runtime `holdDuration` = open time − 1.4 (default 6.6 → doors open ≈ 8 s from 0.85 to the slam). loop, duration 60 |
| end | static, phase end | hold expiry (cancel → jumpToEnd) | doors swing shut 0 … 0.45 s, **SLAM at end + 0.45 s** → server damage + sleep 1 s at end start + 0.45 s; gate sinks 0.85 … 2.0 s |
| pull | link | the mod, once per pulled enemy | source = enemy body centre (feet + 0.9 m, every frame), target = gate void centre = P + M·(0, 3.6, 0). loop; stop at the slam |
| sleep | impact | the mod, once per enemy at the slam | hitPoint = enemy feet, surfaceNormal = up. Dream bubbles / moons ≈ 1.2 s (matches the 1 s sleep) |

Zone radius fixed at 8 m (ring `M01 RangeRing`, `S01 Outer` outer line and `B34 LockShock` show the true range). No `scale` input (would resize pull/sleep too).

## Held layers / hand-offs
- Ground circle (5 layers + pillars): begin copies start at t_s with rot3 = ω·t_s, sustain copies at 1.4 s with rot3 = ω·1.4, same rotLife (HeavenCyclone recipe). Sustain life 59 s, onStop tail 1.4 s (contract 0.4 s → flare 0.5 s → fade).
- Gate solids (frame, spikes, lines, runes, keystone, doors): begin copies rise with exact drag (GD 9.2 m below → 0 in 0.8 s, speed/drag computed), sustain copies (onStop 0) take over at 1.4 s, end copies (`E07…E16 *Hold`) take over at end t = 0, then `*Sink` copies (gravity) take over at 0.85 s. Door angle: open 0 → 1.75 rad over 0.85 … 1.4 s, held 1.75, closed back to 0 over end 0 … 0.45 s.
- Void spirals: sustain onStop tail 0.5 s shrinks them into the closing seam; void fill vanishes at the slam (onStop 0.47).
- Gate solids use alpha materials with `depthWrite` (rim, door, voidFill) and high sortingFudge, so later particles are depth-tested against them.

## Features
Only client-supported features (client_compat check OK, `fix` = 0 changes). High impact: meshParticle (circle line work, gate frame/doors, spirals), bloom, orbitalVelocity, attachedFollow (chant), linkTarget + pathFollow (pull), phasedSequence + stopTail, depthWrite.
Texture memory: 24 unique textures = 3.8 MiB RGBA (the check's WARN 5.6 MiB counts shared textures once per material).
