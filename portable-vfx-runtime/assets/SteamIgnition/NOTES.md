# SteamIgnition — 240 증기 점화 (fire)

Fire into a steam valve (1.0 w × 2.0 h); the valve pressurizes and vents. Non-loop, scale 1, none tintable.
Impact origin = FLOOR under the valve, +Y = world up, +Z = yaw direction = toward the caster.

| system | role | duration | origin / anchor | shows |
|---|---|---|---|---|
| cast | attached | 0.8 s | caster feet, +Z = facing | right hand (x 0.26, y 1.1, z 0.4): growing orange glow, motes gathering in staged bursts, small flame puffs, fast flame-swirl arcs around the hand, small fire sigil (0.27 m) in front of the hand; at 0.6 s a small release flash + flame tongues forward (+Z). |
| ignite | impact | 1.6 s | floor under the valve | 0–0.3 s: two flame-swirl ribbons (stroke-wipe mesh) curl in from the caster side (+Z, 1.8 m) around the valve body at ~1.0 m, with spiralling flame puffs + motes; 0.28 s: ignition flash + glow, fire glow and flames licking up the valve, sparks, dark soot puffs; ground: scorch decal, line-work circle r 0.84 (rings, two hexagrams, vertex circles, ticks) + rune band, shock ring, rim flames; 0.5 s: pressured steam jet from the valve top (y 2.0) up to ~2.8–3.1 m (white stretched jet streaks + expanding steam puffs + spray), small top flash, horizontal pressure ring at 1.6 m (two ring waves + a line-work ring), steam vented sideways from the ring, steam wisps, hot sparks, embers. Ground circle fades by 1.6 s. |

Demo: stand-in box 0.9 × 2.0 × 0.9, caster 2.6 m away; impact starts 0.6 s after the cast (release moment).
Timing constants in the spec: T_IGN = 0.28 s (swirl arrives), T_JET = 0.5 s (jet + pressure ring).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
