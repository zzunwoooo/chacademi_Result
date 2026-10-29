ArchlichMorvath — vfx.json schema 1.21.0
systems: bolt (projectile, 9 layers), bolt_hit (impact, 12 layers), summon (static, 36 layers), grave (static, 13 layers), curse (static, 40 layers), bolt_p2 (projectile, 9 layers), bolt_hit_p2 (impact, 12 layers), summon_p2 (static, 36 layers), grave_p2 (static, 13 layers), curse_p2 (static, 40 layers), storm (static, 51 layers), reap (static, 55 layers), thrall_slash (static, 13 layers), thrall_slash_p2 (static, 13 layers)
required features: additiveBlend, alphaBlend, billboard, bloom, drag, flipbook, gravity, hdrTint, linearVelocity, meshParticle, noise, orbitalVelocity, radialVelocity, rateOverDistance, rotation3D, rotationOverLifetime, stretchedBillboard, tonemapNeutral, worldSpace

vfx.json        the effect (single source of truth; SCHEMA.md defines every field and the reference simulation)
textures/       PNG textures referenced by materials[].texture
preview.html    plays this vfx.json in a browser (backdrop/camera/flight path come from the non-normative "demo" block)
SCHEMA.md       field types, defaults, units, simulation order, noise/drag/curve formulas, attachment contract (§5)
vfx.schema.json JSON Schema for automated validation

Runtime inputs (SCHEMA §5): projectile = position + travel direction per tick; impact = hitPoint, surfaceNormal, incomingDirection
(air hit: surfaceNormal = -incomingDirection); attached = entity feet position + horizontal facing every frame (1.21.0 attachPoint head: headPosition + headRotation of the rendered head every frame, §5.7);
link = source + target world points every frame.

NOTES.md        effect-specific notes: approximations and items the schema cannot express
Unsupported features must be reported, not substituted (features.catalog / tools/check_compat.py).
