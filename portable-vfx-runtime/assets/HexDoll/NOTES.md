# HexDoll (저주 인형, #355 hex_doll, 어둠 중급) — converter notes

A small cute-creepy voodoo doll (~0.85 m: ink-black cloth, violet glowing stitched seams, button eye + X-stitch eye, two pins) stands on a hex curse circle next to the target for 4 s,
tied to the target by a violet thread with pulsing ink beads. Each hit on the doll: doll jolts + ink splash, a pulse runs down the thread, hit flash + hex brand burst on the target.
End: the doll unravels from the feet up into ink threads (flipbook) while the circle flares and collapses. Visual only: damage share, hit detection on the doll entity = the mod.

## Systems and runtime inputs
| system | role | phase | started by | placement |
|---|---|---|---|---|
| dollIn | static | begin (0–0.5 s) | cast | P = doll feet on the ground (spawn point next to the target), M = caster facing |
| doll | static | sustain (loop, duration 60) | t = sustainStart 0.5 s | same |
| dollOut | static | end | hold expiry / cancel (doll killed or removed early) | same |
| form | link | begin | cast | source = **doll chest** = doll feet + (0, 0.5, 0); target = **target chest** (feet + ~0.65·height), both every frame |
| bind | link | sustain | 0.5 s | same |
| fade | link | end | hold expiry / cancel | same |
| strike | link | — (main) | **the mod, one NEW instance per hit on the doll** (caster or ally) | same two points |

- `holdDuration` = 3.5 s (default) → whole spell 4.0 s. Doll destroyed / target dead / dispel → **cancel** (jumpToEnd) = dollOut + fade.
- Doll pop-in at t ≈ 0.26 s (the doll entity/hitbox can become hittable from ~0.3 s). Thread reaches the target at ≈ 0.24 s.
- `strike` timing: t = 0 doll jolt + splash at the doll (= the hit on the doll), pulse travels 0.2 s, **target hit flash at strike + 0.2 s** → apply the shared damage to the target at +0.2 s (or at once; the flash is short enough). Instance done after ≈ 0.6 s; overlapping instances are fine. Do not start strike after the end has begun.
- The strike layers at the doll (jolt flash, jolt arcs) are offset in the link frame by (0, −0.09, 0) from the source, i.e. they assume a roughly level link (target at a similar height); with big height differences they shift slightly — acceptable.

## Held layers
Hex circle (outer, runes, inner, hex sigil), ink pool: one particle each in `doll` (life 59 s, onStop tail 0.4 s: brighten → shrink to 0.25 → fade). dollIn draw-on copies start at t_s with rot3 = ω·t_s,
the sustain copies at 0.5 s with rot3 = ω·0.5 and the same rotLife (ω: outer 0.35, runes 0.22, inner −0.6, hex 0.8 rad/s), so they line up. Doll + doll glow: one particle each, onStop tail 0.05 s;
dollOut E01 (flipbook 4×4, frame 0 = the doll) takes over at end t = 0 at the same size/position.

## Features
`required`: meshParticle, bloom, orbitalVelocity (pins orbit, motes) — high impact; linkTarget + pathFollow (the thread; a straight static beam is not equivalent), phasedSequence, stopTail, flipbook, trails, stretchedBillboard, noise, gravity, drag, additive/alpha blend, hdrTint. No attachedFollow (static doll + link).
All client-supported; `client_compat fix` = 0 changes, `check` OK. 20 unique textures = 3.44 MiB RGBA (the check's WARN 5.3 MiB counts shared textures once per material).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
