const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1920, height: 1080 } }); const rects = {};
  for (const n of ['home','netflix','prime','youtube','results','bear','show',...Array.from({length:13},(_, i)=>'search'+i)]) {
    await p.goto('file://' + path.resolve(`html_${n}.html`)); await p.waitForLoadState('networkidle'); await p.waitForTimeout(300);
    await p.screenshot({ path: `tv_${n}.png` });
    rects[n] = await p.$$eval('[data-id]', els => Object.fromEntries(els.map(e => { const r = e.getBoundingClientRect(); return [e.dataset.id, [r.x, r.y, r.width, r.height]]; })));
  }
  fs.writeFileSync('rects.json', JSON.stringify(rects)); await b.close();
})();
