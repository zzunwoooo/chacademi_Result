# FlameJavelin (화염 투창, 중급 338, id flame_javelin) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| flame_javelin_charge | attached, phase sustain | press (t = 0; `sequence.sustainStart = 0`) | caster feet + facing. Spear forms in the right hand (grip at caster-local [0.42, 1.68, −0.12], spear along +Z, tip 0.95 m ahead). Built-in stages: **0.5 s** (inner hexagram + ground pulse), **1.0 s** (foot sigil + pulse), **1.5 s FULL** (tip star, ring burst, tip sigil locks on, ground flare). After 1.5 s it holds the full-charge look until release. Runtime `holdDuration` = max hold time before auto-cancel (default 10 s, max 30 s). |
| flame_javelin_throw | attached, phase end, endBranch **trigger** | the mod's **trigger** signal on release (any time) | throw flash, push rings, swing arc. The charge layers break up within 0.04 s (onStop jumpToTail). **Launch the projectile at the same tick.** |
| flame_javelin_fizzle | attached, phase end, endBranch **expire** | cancel (`jumpToEnd`, e.g. interrupted / weapon swapped) or hold expiry | the spear crumbles to ash and embers. |
| flame_javelin | projectile | the mod at release | **P = the spear TIP** (start = caster feet + facing·[0.42, 1.68, 0.83] in the caster frame, i.e. the tip in the hand). +Z = travel (follows the arc tangent). Speed / range from the server (scale with the charge). Held spear = one particle per layer (life 60 s, duration 60). World wake (heat rings, ribbons, flames, sparks, embers, smoke) uses rateOverDistance. |
| flame_javelin_hit | impact | hit, **charge < 1.5 s** | weak: explosion radius ring **1.5 m**, **explosion / damage at +0.16 s**. |
| flame_javelin_hit_full | impact | hit, **full charge (≥ 1.5 s)** | full: explosion radius ring **2.5 m**, **explosion / damage at +0.24 s**. |

Impact inputs: hitPoint = tip contact point, surfaceNormal = block face normal (entity / air hit: −incomingDirection), incomingDirection = travel.
The spear is drawn stuck in the surface (tilted 40° along the incoming direction, tip buried at hitPoint) and heats up (converging rings, inflow, contracting fire ring) until the explosion.
The thin line ring on the ground (H11 Bound, size = radius) shows the real explosion radius from the hit moment; if the server radius differs, scale that layer's `start.size` (or tell us the radius).
If the mod uses a continuous charge value, choose `_full` at charge ≥ 1.5 s, else the weak one.

## Held layers
Charge held layers (circle, rune band, hexagram, sigil, spear shaft/blade, glow, tip sigil/ring) are ONE particle each, life 59 s; alpha/size keys are placed at absolute charge times
(key = (t − born) / 59), so the brightness steps at 0.5 / 1.0 / 1.5 s line up for any release time. Periodic layers use bursts with cycles (no rateOverTime), counts growing per stage;
after 1.5 s they loop (cycles cover hold.max 30 s). All charge layers have onStop tails ≤ 0.2 s.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK). High impact: meshParticle (spear blade/shaft crossed planes, flame sheath, wisps, heat rings, coils, foot circle line work, boundary ring), bloom,
attachedFollow (charge / throw / fizzle). Also phasedSequence + triggeredEnd (release = trigger), stopTail, rateOverDistance + worldSpace (wake), flipbook, stretchedBillboard, rotation3D, noise, gravity, drag, radialVelocity.
orbitalVelocity is not used.
Textures: 16 unique PNGs ≤ 512 px = 2.75 MiB RGBA (the check's WARN 4.3 MiB counts shared textures once per material).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
