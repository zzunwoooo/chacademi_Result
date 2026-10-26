# DreadDream (악몽, #349, dread_dream) — converter notes

Single-target dark spell. Gameplay (owner plan): if the target is **asleep or feared** → big damage and that state is removed; otherwise **fear 1 s**.
The VFX only shows the spell: the mod picks the branch, applies damage / fear, and shows the debuff icon.

## Systems and runtime inputs
| system | role | started by | placement |
|---|---|---|---|
| dread_dream_cast | attached | cast t = 0, caster | caster feet + facing. Eye sigil at the right hand (local [0.3, 1.35, 0.55]), release flash at +0.10 s, foot circle |
| dread_dream_thread | link | cast t = 0 | source = caster hand point (feet + facing·[0.3, 1.35, 0.55]), target = target chest (feet + 0.7·height, preview 1.25 m); both every frame. The thread head reaches the target at **+0.14 s** |
| dread_dream_nightmare | attached | **+0.14 s**, on the target, ONLY when the target is asleep/feared (empowered) | target feet + facing. Ink maw blooms over the head; jaws open 0.08–0.43, snap shut at **+0.48 s = damage moment** (cast t ≈ 0.62 s) → shatter, shards, waves. Done ~1.6 s |
| dread_dream_fear | attached | **+0.14 s**, on the target, otherwise (normal) | target feet + facing. Shadow eye blinks open over the head; glare + fear ripple at **+0.22 s = fear applied** (cast t ≈ 0.36 s); eye closes ~0.95 s, matching the 1 s fear |

- Start exactly one of nightmare / fear per cast. Both are one-shot (loop false), no stop signal needed.
- If the target dies / despawns early: stop emitting + clear local layers (SCHEMA §5 attached).
- The maw is authored for a 1.8 m target (maw centre 2.5 m above the feet). No `scale` input (instanceScale would also scale the cast/thread; `scale.systems` is not in the client yet). For very tall mobs the maw sits lower on the body — acceptable.
- Thread range: the link stretches to any distance; tested at ~6 m.

## Mechanics of the bite
Jaws are two alpha billboards of one texture (lower = rotated 180°). Open copies fly apart with drag (exact §4.2 integration baked into the snap copies' start offsets), snap copies start at 0.43 s at the open position and close in 0.05 s, so the jaws meet exactly at 0.48 s.
The eye (flipbook 4×4: closed → open → slit pupil → closing) blinks within each lifetime.

## Features
Client-supported only (client_compat fix = 0 changes, check OK). High impact: meshParticle (ground dream circles, rune bands, curling arcs), bloom, orbitalVelocity (dream moons, motes), attachedFollow (cast / nightmare / fear),
plus linkTarget + pathFollow (thread), flipbook (eye blink, stroke wipes), trails, stretchedBillboard, noise, drag, gravity, radialVelocity, rotation3D.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
