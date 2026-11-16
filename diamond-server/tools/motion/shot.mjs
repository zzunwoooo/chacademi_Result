// 사용법: PW=$(npm root -g)/playwright/index.mjs node shot.mjs <preview.html> <out.png> [틱,틱,...]
const { chromium } = await import(process.env.PW);
const [,, html, out, tickArg] = process.argv;
const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1100, height: 900 } });
const errs = []; p.on('pageerror', e => errs.push(e.message));
await p.goto('file://' + html);
const ticks = tickArg ? tickArg.split(',').map(Number) : await p.evaluate(() => { const s = META.phases.map(x => x[0]); return [...new Set([...s, META.castTick ?? s[1], ANIM.emote.endTick])].sort((a, b) => a - b).slice(0, 6); });
const shots = [];
for (const view of ['35', '90']) {
  await p.click(`[data-view="${view}"]`);
  for (const t of ticks) {
    await p.evaluate(t => { const s = document.getElementById('scrub'); s.value = t; s.dispatchEvent(new Event('input')); }, t);
    await p.waitForTimeout(80);
    shots.push({ view, t, buf: await p.locator('#cv').screenshot() });
  }
}
// 한 장으로 합치기 (위: 3/4, 아래: 측면)
const cols = ticks.length;
const page2 = await b.newPage({ viewport: { width: 320 * cols, height: 480 } });
await page2.setContent(`<body style="margin:0;display:grid;grid-template-columns:repeat(${cols},320px);background:#fff">` +
  shots.map(s => `<div style="position:relative"><img style="width:320px;height:240px;display:block" src="data:image/png;base64,${s.buf.toString('base64')}"><span style="position:absolute;left:4px;bottom:4px;font:12px sans-serif;background:#fff8">${s.view === '35' ? '3/4' : '측면'} t=${s.t}</span></div>`).join('') + '</body>');
await page2.screenshot({ path: out });
await b.close();
console.log(errs.length ? 'PAGE ERRORS: ' + errs.join(' | ') : 'OK ' + out);
