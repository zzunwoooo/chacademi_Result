# CalmRipple (잔잔한 물결, 생활 #378, id calm_ripple) — converter notes

## System and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| calm_ripple | attached | cast (t = 0), once | anchor = caster feet, basis = caster horizontal facing (+Z forward, +X right). duration 5.2 s, loop false, no stop signal; everything is gone by ≈5.0 s. Right (fishing) hand centre = (0.29, 0.86, 0.02); wrist bead = (0.375, 0.86, 0.02). |

Timeline: 0.00 still deep-water pool opens under the feet (r 1.3 m), first thin ripple leaves the feet, ring + water-rune band (waves / drops / ripples / fishhook) + lotus petals + koi-and-drop sigil draw on and turn slowly (counter-rotating) → ripples keep spreading slowly to r ≈ 2.4 m at 0.05 / 0.4 / 0.78 / 1.5 / 1.9 / 2.35 s (each a thin line ring with a dark trough ring just inside), small wavelets at the feet, caustic light web on the pool floor, slow droplets lift and fall back with tiny splash rings, calm motes rise → 0.45–1.1 a water ribbon (3 stacked helix strips, 2x8 wipe) rises from the pool and spirals once around the right hand, droplets drawn up with it → **1.10 s "calm"**: the ribbon settles into a glowing water bead on the right wrist — soft serene sparkle, chime rings, tiny splash, circle/lotus/sigil flare copies, one wider brighter ripple (r ≈ 2.9 m) → 1.3–2.6 circle and caustics fade → 1.1–5.0 bead buff mark (bead + glow + small tilted orbit ring + orbiting motes + a drip every 0.6 s), winks out at ≈4.75 s.

**Recommended mod timing:** grant the buff (next fishing attempt: tension rise −30 %) at cast +1.1 s (bead forms), or at t = 0 for an instant cast. The buff indicator itself is the server UI; this VFX is only the cast + a ≈4 s wrist mark. Nothing needs stopping on cancel (local layers follow the feet; rising motes/drops are world-space).

Rotation: circle layers rotate at fixed rates (outer 0.18, runes −0.26, lotus 0.12, sigil −0.2 rad/s); the flare copies start with rot3 = ω·(tFlare − born) so they overlay their layer exactly. Not a combat spell: no range ring.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (circle line work, rune band, lotus, ripple rings/troughs, helix ribbon, bead ring), **bloom**, **attachedFollow**, **orbitalVelocity** (ribbon droplets H04 + bead orbit motes M04).
Others: flipbook, worldSpace, stretchedBillboard, trails, noise, drag, gravity, radialVelocity, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.
Texture memory: 14 unique textures (≤ 512 px).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
