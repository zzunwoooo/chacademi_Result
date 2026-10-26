# SignalFlare (봉화 신호 #376, signal_flare) — converter notes
- One static system `signal_flare`, P = caster feet at cast, identity/facing basis (symmetric, facing irrelevant). The mod starts it once at cast; it stops emitting at 12.75 s by itself and is fully gone by ≈ 14.5 s.
- Timeline: 0.00 launch circle draws on · 0.55 launch (flash, ring, jet, smoke) · flare climbs 24 m (1.0 → 25 m) in 0.90 s (speed + drag, exact per SCHEMA §4.2) · 1.45 burst at y = 25 m (crown star, 3 waves, spokes, willow sparks, ash/smoke) · 1.6 – 12.7 beacon (held star + small crown at 25 m, pulsing glow/rings, light column 0 → 25 m, rising embers/smoke) · 12.7 – 14.5 fade-out.
- Visual beacon ≈ 13 s after the burst; the 30 s party map/minimap marker + notification is UI (not part of this pack).
- Periodic emitters are authored as bursts with cycles/interval inside the single system (no rate), so nothing needs a second start signal.
- Readability at range: the crown (15–18 m billboard) and the beacon star (4.2 m) + column are additive HDR; checked at 60 m in the preview (scenario "60 m 밖").
- Not used: orbit-only/head attach/lights etc. Uses meshParticle (launch circle, column, jet), bloom, orbitalVelocity (small orbit on gather sparks / rising embers), trails, flipbook, noise, drag, gravity, stretched billboards, world-space velocity.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
