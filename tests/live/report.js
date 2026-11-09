// Reads the report with the default settings and checks the date logic against the seeded pages.
// Run seed.js first. Expects default settings (60 months, 60/30/7 days) and no policy date.
const path = require('path');
const L = require('./lib');
(async () => {
  const { browser, page: p } = await L.open();
  const rows = {};
  for (const f of ['due', 'stale', 'all']) {
    await p.goto(L.adminUrl('contentExpiryReport', '&ceFilter=' + f), { waitUntil: 'load' });
    await p.waitForTimeout(2000);
    rows[f] = await p.$$eval('table tr', r => r.map(x => x.innerText.replace(/\s+/g, ' ').trim()));
    await p.screenshot({ path: path.join(L.OUT, `report_${f}.png`), fullPage: true });
  }
  const has = (f, ref) => rows[f].some(r => r.includes(ref));
  L.check(has('stale', 'Old.Ancient.WebHome') && has('stale', 'Old.Stale.WebHome'), 'pages past retention are in the stale filter');
  L.check(!has('stale', 'Old.Soon.WebHome') && !has('stale', 'Old.Mid.WebHome'), 'pages inside retention are not stale');
  L.check(has('due', 'Old.Soon.WebHome'), 'page whose retention ends inside the warning window is due');
  L.check(!has('due', 'Old.Mid.WebHome') && !has('due', 'Old.Fresh.WebHome'), 'young pages are not due');
  L.check(rows.all.some(r => /Old\.Ancient\.WebHome.*Has child pages/.test(r)), 'page with children is flagged');
  L.check(rows.all.some(r => /Sandbox\.WebHome.*Excluded/.test(r)), 'extension pages are marked excluded');
  L.check(rows.due.every(r => !/Old\.Stale\.WebHome/.test(r) || /in 60 days/.test(r)), 'safety floor holds expiry at today + longest warning');
  await browser.close();
  L.done();
})();
