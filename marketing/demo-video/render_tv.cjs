const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 1536, height: 856 }, deviceScaleFactor: 1.25 });
  let state = 'choose';
  await page.route('http://tv.local/**', r => {
    const u = r.request().url();
    if (u.includes('/s?')) return r.fulfill({ contentType: 'application/json', body: fs.readFileSync(`state_${state}.json`, 'utf8') });
    if (u.includes('/f?') || u.includes('/e?') || u.includes('/a?')) return r.fulfill({ contentType: 'application/json', body: '{}' });
    return r.fulfill({ contentType: 'text/html', body: fs.readFileSync('page.html', 'utf8') });
  });
  for (const s of ['choose', 'picks', 'playing']) {
    state = s;
    await page.goto('http://tv.local/picks?k=x');
    await page.waitForTimeout(1500);
    await page.mouse.move(-10, -10);
    await page.screenshot({ path: `tv_${s}.png` });
  }
  await b.close();
})();
