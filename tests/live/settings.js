// Settings form: server-side validation, persistence and author stamping. Leaves settings changed
// (48 months, 90/30/7 days, excluded space Seed, enabled, report-only).
const path = require('path');
const L = require('./lib');
(async () => {
  const { browser, page: p } = await L.open(L.ADMIN, { width: 1280, height: 1800 });
  const save = async () => { await p.click('input[type=submit][value=Save], button:has-text("Save")'); await p.waitForLoadState('load'); await p.waitForTimeout(1500); };
  await p.goto(L.adminUrl('contentExpiry'), { waitUntil: 'load' });
  await p.evaluate(() => { const e = document.querySelector('input[name=ceRetentionMonths]'); e.removeAttribute('max'); e.removeAttribute('min'); e.value = '5000'; });
  await p.fill('input[name=ceWarningDays]', '7, x, 30');
  await save();
  L.check(/Nothing was saved/.test(await p.innerText('body')), 'out-of-range retention and bad warning days are rejected');
  await p.goto(L.adminUrl('contentExpiry'), { waitUntil: 'load' });
  await p.check('input[name=ceEnabled]');
  await p.fill('input[name=ceRetentionMonths]', '48');
  await p.fill('input[name=ceWarningDays]', '90, 30, 7');
  await p.fill('textarea[name=ceExcludedSpaces]', 'Seed');
  await save();
  const body = await p.innerText('body');
  L.check(/Settings saved/.test(body) && !/being ignored/.test(body), 'valid settings save and are trusted', body.split('\n').filter(l => /ignored|saved/i.test(l)).join(' | '));
  await p.goto(L.adminUrl('contentExpiry'), { waitUntil: 'load' });
  const v = [await p.inputValue('input[name=ceRetentionMonths]'), await p.inputValue('input[name=ceWarningDays]'), String(await p.isChecked('input[name=ceEnabled]'))];
  L.check(v.join('|') === '48|90, 30, 7|true', 'values persist after reload', v.join('|'));
  const r = await L.rest('GET', 'wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/Settings');
  L.check(/<author>XWiki\.superadmin/.test(r.text), 'settings page author is the acting admin', (r.text.match(/<author>[^<]*/) || [''])[0]);
  await p.screenshot({ path: path.join(L.OUT, 'settings_saved.png'), fullPage: true });
  await browser.close();
  L.done();
})();
