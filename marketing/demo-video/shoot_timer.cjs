const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1536, height: 864 }, deviceScaleFactor: 1.25 });
  let rem = 600000;
  await p.route('http://tv.local/**', r => {
    const u = r.request().url();
    if (u.includes('/state')) { const now = Date.now(); return r.fulfill({ contentType: 'application/json', body: JSON.stringify({ now, running: true, endsAt: now + rem, total: 600000 }) }); }
    return r.fulfill({ contentType: 'text/html', body: fs.readFileSync('timer.html', 'utf8') });
  });
  for (const s of [600, 599, 598, 597, 596]) {
    rem = s * 1000 - 300; await p.goto('http://tv.local/timer'); await p.waitForTimeout(1300);
    await p.screenshot({ path: `tv_timer${600 - s}.png` });
  }
  await b.close();
})();
