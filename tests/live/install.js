// Uploads and imports the XAR, then loads both admin sections and fails on any macro error.
// Usage: node install.js [path-to-xar]   (default: newest dist/*.xar)
const fs = require('fs'), path = require('path');
const L = require('./lib');
(async () => {
  const dist = path.join(__dirname, '../../dist');
  const xar = process.argv[2] || path.join(dist, fs.readdirSync(dist).filter(f => f.endsWith('.xar')).sort().pop());
  const { browser, page: p } = await L.open();
  await p.goto(L.adminUrl('Import').replace('&section', '&section'), { waitUntil: 'domcontentloaded' });
  await p.setInputFiles('input[type=file]', xar);
  await p.waitForTimeout(6000);
  await p.click('text=' + path.basename(xar) + ' >> nth=0');
  await p.waitForTimeout(3000);
  await p.click('input[value="Import"], button:has-text("Import")');
  await p.waitForTimeout(8000);
  const summary = (await p.innerText('#packagecontainer')).split('\n').filter(l => /Page\(s\)/.test(l)).join(' / ');
  console.log(summary);
  L.check(/0 Page\(s\) with error/.test(summary) && /[1-9]\d* Page\(s\) installed/.test(summary), 'XAR imports without errors', summary);
  for (const s of ['contentExpiry', 'contentExpiryReport']) {
    await p.goto(L.adminUrl(s), { waitUntil: 'load' });
    await p.waitForTimeout(2500);
    const err = (await p.innerText('body')).match(/Failed to execute[^\n]*/g);
    L.check(!err, `admin section ${s} renders`, err && err[0].slice(0, 300));
    await p.screenshot({ path: path.join(L.OUT, s + '.png'), fullPage: true });
  }
  await browser.close();
  L.done();
})();
