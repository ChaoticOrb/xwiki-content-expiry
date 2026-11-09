// Warn mode end to end: settings form, who is emailed, thresholds over time, no duplicates, retries.
// Time is stepped with engine.runAt(date) so the whole 60/30/7 sequence runs in seconds.
const path = require('path');
const L = require('./lib');
const F = require('./fixtures');
const M = require('./mail');

const DAY = 86400000;
let runNo = 0;
// One engine run at t0 + offset. Safe to repeat in one script: each gets its own variable and result keys.
const run = (offsetMs) => { const n = ++runNo; return `def r${n} = e.runAt(new Date(t0 + ${offsetMs}L)); k('ran', r${n}.ran); k('pages', r${n}.pages); k('emails', r${n}.emails); k('failed', r${n}.failed.size()); k('message', r${n}.message)`; };
const engine = (steps) => L.groovy(`def e = x.parseGroovyFromPage('ContentExpiry.Code.Engine', ctx)\ndef t0 = System.currentTimeMillis()\n${steps}`, 'WarnRun');
const to = (box) => box.map(m => m.to).sort().join(' ');

async function resetPolicy() { for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`); }

(async () => {
  for (const [n, e] of [['alice', 'alice@example.test'], ['bob', 'bob@example.test'], ['carol', ''], ['joe', 'joe@example.test']]) await F.ensureUser(n, e);
  await F.addToAdminGroup('joe');
  await F.seedWarnPages();
  await resetPolicy();
  M.failMode(false); M.clear();

  // Settings through the real form, in warn mode, limited to the Warn space.
  const { browser, page: p } = await L.open(L.ADMIN, { width: 1280, height: 1900 });
  await p.goto(L.adminUrl('contentExpiry'), { waitUntil: 'load' });
  await p.check('input[name=ceEnabled]');
  await p.check('input[name=ceMode][value=warn]');
  await p.fill('input[name=ceRetentionMonths]', '60');
  await p.fill('input[name=ceWarningDays]', '60, 30, 7');
  await p.fill('textarea[name=ceIncludedSpaces]', 'Warn');
  await p.fill('input[name=ceSenderAddress]', 'wiki@example.test');
  await p.click('input[type=submit][value=Save]');
  await p.waitForLoadState('load'); await p.waitForTimeout(1500);
  const body = await p.innerText('body');
  L.check(/Settings saved/.test(body), 'warn mode settings save through the form', (body.match(/Nothing was saved|[^\n]*error[^\n]*/i) || [''])[0]);
  L.check(/runs at 03:17 as/.test(body), 'settings page says who the daily job runs as');
  await p.screenshot({ path: path.join(L.OUT, 'warn_settings.png'), fullPage: true });
  await browser.close();

  // Invalid sender is rejected.
  let o = await engine(`k('r', e.saveSettings([enabled: '1', mode: 'warn', retentionMonths: '60', warningDays: '60, 30, 7', includedSpaces: 'Warn', senderAddress: 'not an address']).errors)`);
  L.check(/senderAddress/.test(o.r || ''), 'a malformed sender address is rejected', o.r || o.error);
  // That rejected save changed nothing: the form's settings are still in force.

  // Day 0: first run warns at 60 days.
  o = await engine(run(0)); await M.wait(1500);
  let box = M.inbox();
  L.check(o.ran === 'true' && o.pages === '5' && o.emails === '4', 'first run warns about 5 pages in 4 emails', o.message || o.error);
  L.check(to(box) === 'alice@example.test bob@example.test joe@example.test webmaster@xwiki.org', 'recipients are the owners, creators and the fallback group', to(box));
  const alice = box.find(m => m.to === 'alice@example.test'), bob = box.find(m => m.to === 'bob@example.test');
  L.check(alice && /Alice old page/.test(alice.text) && /Child page/.test(alice.text) && !/Parent page/.test(alice.text), 'alice gets her two pages and not the parent that has children');
  L.check(bob && /Bob old page/.test(bob.text) && /Owned by Bob page/.test(bob.text), 'bob gets his page plus the one he owns although alice created it');
  L.check(box.every(m => !/\$|#if|#foreach/.test(m.subject + m.text.split('\n').filter(l => !/^(X-|Content-)/.test(l)).join(' '))), 'no Velocity left unrendered in subject or body');
  L.check(box.every(m => /Warn\/|Warn%2F/.test(m.text)) && box.some(m => /http:\/\/localhost:8080\/xwiki\/bin\/view\/Warn\//.test(m.text)), 'emails link to the pages with absolute URLs');
  L.check(!box.some(m => /Fresh page/.test(m.text)), 'a recently edited page is not warned about');

  // Same time again, and an hour later: nothing new.
  const before = M.inbox().length;
  o = await engine(run(0) + '\n' + run(3600000)); await M.wait(1000);
  L.check(!o.error && M.inbox().length === before, 'running again sends nothing twice', o.error || `${M.inbox().length - before} extra`);

  // Day 31: crosses the 30-day threshold.
  M.clear();
  o = await engine(run(31 * DAY)); await M.wait(1500); box = M.inbox();
  L.check(o.pages === '5' && box.length === 4, 'day 31 sends the 30-day warning', o.message || o.error);
  L.check(box.some(m => /in 29 days/.test(m.text)), 'the 30-day email counts 29 days left');
  o = await engine(run(31 * DAY + 3600000)); await M.wait(800);
  L.check(M.inbox().length === 4, 'no duplicate on the same day');

  // Day 55 catches up the 7-day warning (moment was day 53) with a gap of 24 days since the last run.
  M.clear();
  o = await engine(run(55 * DAY)); await M.wait(1500); box = M.inbox();
  L.check(o.pages === '5' && box.length === 4 && box.some(m => /in 5 days/.test(m.text)), 'a gap between runs still delivers the 7-day warning', o.message || o.error);

  // Nothing is left to warn about afterwards.
  M.clear();
  o = await engine(run(70 * DAY)); await M.wait(800);
  L.check(M.inbox().length === 0 && o.pages === '0', 'after the last threshold nothing more is sent', o.message || o.error);

  // Failure and retry: refuse mail, then let it through.
  await resetPolicy();
  await engine(`k('s', e.saveSettings([enabled: '1', mode: 'warn', retentionMonths: '60', warningDays: '60, 30, 7', includedSpaces: 'Warn', senderAddress: 'wiki@example.test']))`);
  M.clear(); M.failMode(true);
  o = await engine(run(0)); await M.wait(1000);
  L.check(o.failed === '4' && M.inbox().length === 0, 'with the mail server refusing, every email is reported as failed', o.message || o.error);
  M.failMode(false);
  o = await engine(run(2 * 3600000)); await M.wait(1500); box = M.inbox();
  L.check(box.length === 4 && /retried/.test(o.message || ''), 'failed warnings are retried on the next run', o.message || o.error);
  M.clear();
  o = await engine(run(3 * 3600000)); await M.wait(800);
  L.check(M.inbox().length === 0, 'retries stop once delivered');

  await resetPolicy();
  M.failMode(false);
  L.done();
})();
