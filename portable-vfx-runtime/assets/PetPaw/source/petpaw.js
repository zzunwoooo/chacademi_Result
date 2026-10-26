// PetPaw — companion pet buff/skill circle (static, one-shot ~1.2 s, P = ground under the pet's feet, +Z = pet facing).
// Small magic circle (diameter ~1.6 m) of mesh line work: outer double ring, paw-glyph rune band, inner ring with 8 small circles,
// and a crisp dog paw print (pad + 4 toes, PNG) in the middle. Spreads in slightly, holds, spreads out and fades; a few sparkles rise.
//   circle        gold / soft pink (normal pet)
//   circle_shiny  teal / violet  (shiny / colour-variant pet) — same shapes and timing, own materials.
const TWO = 2 * Math.PI;

function strip(cx, cz, r, hw, seg) { // flat ring strip in XZ at y 0, U along the ring (phi = -s*2pi), V 0 inner → 1 outer
  return ribbon(seg, s => { const phi = -s * TWO, d = [Math.cos(phi), 0, Math.sin(phi)], c = [cx, 0, cz];
    return [add(c, mul(d, r - hw)), add(c, mul(d, r + hw))]; });
}
function arcBand(r0, r1, a0, a1, seg) { // band segment mapping U 0..1 onto the rune strip
  return ribbon(seg, s => { const phi = -(a0 + s * (a1 - a0)), d = [Math.cos(phi), 0, Math.sin(phi)];
    return [mul(d, r0), mul(d, r1)]; });
}
function merge(parts) {
  const verts = [], uvs = [], tris = [];
  for (const p of parts) { const o = verts.length; verts.push(...p.verts); uvs.push(...p.uvs); tris.push(...p.tris.map(i => i + o)); }
  return { verts, uvs, tris };
}
const dots = [];
for (let k = 0; k < 8; k++) { const a = k * TWO / 8 + TWO / 16; dots.push(strip(0.515 * Math.cos(a), 0.515 * Math.sin(a), 0.032, 0.007, 20)); }
const bands = [];
for (let k = 0; k < 4; k++) bands.push(arcBand(0.605, 0.715, k * TWO / 4, (k + 1) * TWO / 4, 40));

var EXTRA_MESHES = {
  ppOuter: merge([strip(0, 0, 0.785, 0.018, 128), strip(0, 0, 0.745, 0.008, 128)]),
  ppRune: merge(bands),
  ppInner: merge([strip(0, 0, 0.585, 0.008, 112), strip(0, 0, 0.452, 0.011, 96), strip(0, 0, 0.42, 0.005, 96)].concat(dots)),
};

const PAL = {
  g: { key: "Gold",  WH: [1, 0.97, 0.9],  A: [1, 0.84, 0.5],  B: [1, 0.68, 0.76], lt: [1.9, 1.65, 1.35], ink: [0.12, 0.06, 0.08] },
  s: { key: "Shiny", WH: [0.9, 1, 1],     A: [0.42, 0.96, 0.92], B: [0.72, 0.52, 1], lt: [1.4, 1.8, 2.0], ink: [0.05, 0.06, 0.12] },
};
const FLAT = (extra) => Object.assign({ mode: "mesh", pos: [0, 0.03, 0], rot3: [0, [0, TWO], 0] }, extra);
// common circle envelope: spreads in (0.6 → 1.04 → 1), holds, spreads out a little while fading
const SPREAD = [[0, 0.6, 0, 3], [0.2, 1.04], [0.32, 1], [0.78, 1.0], [1, 1.1]];
const FADE = [[0, 0], [0.12, 1], [0.72, 0.85], [1, 0]];

function layers(p, x) {
  const M = n => n + "_" + x;
  return [
    FLAT({ name: "01 InkUnder", mat: M("ink"), mesh: "quad", fudge: 50, pos: [0, 0.015, 0], bursts: [[0, 1]], life: 1.15, size: 1.95,
      alpha: [[0, 0], [0.15, 0.32], [0.7, 0.28], [1, 0]], sizeLife: [[0, 0.7, 0, 2], [0.25, 1], [1, 1.05]] }),
    FLAT({ name: "02 FloorGlow", mat: M("floor"), mesh: "quad", fudge: 45, pos: [0, 0.022, 0], bursts: [[0, 1]], life: 1.15, size: 1.78,
      rotLife: [0, 0.4, 0], alpha: [[0, 0], [0.14, 0.42], [0.7, 0.3], [1, 0]], sizeLife: SPREAD }),
    FLAT({ name: "03 OuterRings", mat: M("line"), mesh: "ppOuter", fudge: 30, bursts: [[0, 1]], life: 1.15, size: 1,
      rotLife: [0, 0.7, 0], color: [[0, p.WH], [0.3, p.A], [1, p.B]], alpha: FADE, sizeLife: SPREAD }),
    FLAT({ name: "04 RuneBand", mat: M("rune"), mesh: "ppRune", fudge: 28, pos: [0, 0.035, 0], bursts: [[0.05, 1]], life: 1.1, size: 1,
      rotLife: [0, -1.1, 0], color: [[0, p.WH], [0.35, p.B], [1, p.A]], alpha: [[0, 0], [0.16, 0.95], [0.72, 0.8], [1, 0]], sizeLife: SPREAD }),
    FLAT({ name: "05 InnerRing", mat: M("line"), mesh: "ppInner", fudge: 26, pos: [0, 0.04, 0], bursts: [[0.09, 1]], life: 1.06, size: 1,
      rotLife: [0, 1.5, 0], color: [[0, p.WH], [0.4, p.A], [1, p.A]], alpha: [[0, 0], [0.16, 0.95], [0.72, 0.85], [1, 0]], sizeLife: SPREAD }),
    { name: "06 Paw", mat: M("paw"), mode: "mesh", mesh: "quad", fudge: 20, pos: [0, 0.05, 0], bursts: [[0.1, 1]], life: 1.0, size: 0.72,
      alpha: [[0, 0], [0.1, 1], [0.72, 1], [1, 0]], sizeLife: [[0, 0.35, 0, 4], [0.18, 1.12], [0.3, 1], [1, 0.97]] },
    { name: "07 PawEcho", mat: M("paw"), mode: "mesh", mesh: "quad", fudge: 22, pos: [0, 0.055, 0], bursts: [[0.18, 1]], life: 0.42, size: 0.72,
      alpha: [[0, 0.55], [1, 0]], sizeLife: [[0, 1, 0, 1.2], [1, 1.55]] },
    FLAT({ name: "08 ShockRing", mat: M("shock"), mesh: "quad", fudge: 24, pos: [0, 0.045, 0], bursts: [[0.03, 1]], life: 0.45, size: 1.9,
      color: [[0, p.WH], [1, p.B]], alpha: [[0, 0.75], [1, 0]], sizeLife: [[0, 0.3, 0, 2.5], [1, 1.02]] }),
    { name: "09 Flash", mat: M("glow"), fudge: 10, pos: [0, 0.18, 0], bursts: [[0.08, 1]], life: 0.26, size: 0.85,
      color: [[0, p.WH], [1, p.A]], alpha: [[0, 0.4], [1, 0]], sizeLife: [[0, 0.6], [0.3, 1], [1, 1.05]] },
    { name: "10 Sparkles", mat: M("star"), fudge: -6, bursts: [[0.14, 3], [0.32, 3], [0.52, 2]], life: [0.45, 0.62], size: [0.09, 0.15],
      shape: { type: "circle", r: 0.62, th: 0.7, y: 0.06 }, vel: { y: [0.7, 1.2] }, drag: 0.8, rot: [0, TWO], rotZ: [-2, 2],
      color: [[0, p.WH], [1, p.B]], alpha: [[0, 0], [0.2, 1], [0.6, 0.8], [1, 0]], sizeLife: [[0, 0.3], [0.25, 1], [1, 0.25]] },
    { name: "11 Motes", mat: M("dot"), fudge: -4, bursts: [[0.1, 6], [0.38, 6]], life: [0.55, 0.75], size: [0.03, 0.05],
      shape: { type: "circle", r: 0.75, th: 0.5, y: 0.04 }, vel: { y: [0.45, 0.95], orb: [0.6, 1.0] }, noise: { s: 0.15, f: 1.0, sp: 0.5 },
      color: [[0, p.A], [1, p.B]], alpha: [[0, 0], [0.2, 0.9], [0.7, 0.7], [1, 0]] },
  ];
}

const MATS = {};
for (const [x, p] of Object.entries(PAL)) {
  const t = p.lt, k = p.key;
  MATS["ink_" + x]   = { tex: "T_PP_Ink", tint: [1, 1, 1], add: false };
  MATS["floor_" + x] = { tex: "T_PP_Floor_" + k, tint: [1.1, 1.1, 1.1], add: true };
  MATS["line_" + x]  = { tex: "T_PP_Line", tint: t, add: true };
  MATS["rune_" + x]  = { tex: "T_PP_Rune", tint: t.map(v => v * 0.95), add: true };
  MATS["paw_" + x]   = { tex: "T_PP_Paw_" + k, tint: [1.5, 1.5, 1.5], add: true };
  MATS["shock_" + x] = { tex: "T_PP_Shock", tint: t.map(v => v * 0.8), add: true };
  MATS["glow_" + x]  = { tex: "T_PP_Glow", tint: t.map(v => v * 0.6), add: true };
  MATS["star_" + x]  = { tex: "T_PP_Star4", tint: t.map(v => v * 1.2), add: true };
  MATS["dot_" + x]   = { tex: "T_PP_Dot", tint: t, add: true };
}

const L = [-1.0, 0, 0], R = [1.0, 0, 0];
const SCENE = {
  name: "PetPaw",
  bloom: { threshold: 0.95, intensity: 1.0, scatter: 0.6, tint: [1.0, 0.95, 0.92] }, tonemapping: "neutral",
  systems: [
    { id: "circle",       role: "static", loop: false, duration: 0.6, layers: layers(PAL.g, "g") },
    { id: "circle_shiny", role: "static", loop: false, duration: 0.6, layers: layers(PAL.s, "s") },
  ],
  demo: {
    interval: 1.8, bg: [0.17, 0.19, 0.25], floor: [0.24, 0.27, 0.22], grid: [0.2, 0.23, 0.18],
    scenarios: {
      both:  { label: "기본 + 변색종", from: [0, 0, 0], to: [0, 0, 0], normal: [0, 1, 0], flight: 0.01,
               place: { circle: { at: L, start: 0 }, circle_shiny: { at: R, start: 0 } },
               camera: { target: [0, 0.2, 0], dist: 4.2, yaw: -90, pitch: 38 } },
      close: { label: "기본 가까이", from: [0, 0, 0], to: [0, 0, 0], normal: [0, 1, 0], flight: 0.01,
               place: { circle: { at: L, start: 0 }, circle_shiny: { at: R, start: 0 } },
               camera: { target: [-1.0, 0.1, 0], dist: 2.4, yaw: -90, pitch: 55 } },
      closeS:{ label: "변색종 가까이", from: [0, 0, 0], to: [0, 0, 0], normal: [0, 1, 0], flight: 0.01,
               place: { circle: { at: L, start: 0 }, circle_shiny: { at: R, start: 0 } },
               camera: { target: [1.0, 0.1, 0], dist: 2.4, yaw: -90, pitch: 55 } },
    },
  },
};
