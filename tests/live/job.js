// The scheduled job: runs as the admin who last saved the settings (joe, who is an admin but
// not superadmin and has no programming rights), and does what runDaily does.
const path = require('path');
const L = require('./lib');
const F = require('./fixtures');
const M = require('./mail');

const JOE = { user: 'joe', pass: 'joepass1' };

(async () => {
  for (const [n, e] of [['alice', 'alice@example.test'], ['bob', 'bob@example.test'], ['carol', ''], ['joe', 'joe@example.test']]) await F.ensureUser(n, e);
  await F.addToAdminGroup('joe');
  await F.seedWarnPages();
  for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
  M.failMode(false); M.clear();

  // joe saves the settings. Superadmin sessions from earlier tests may still hold XWiki's admin page lock.
  await L.releaseAdminLock();
  const j = await L.open(JOE, { width: 1280, height: 1900 });
  await j.page.goto(L.adminUrl('contentExpiry'), { waitUntil: 'load' });
  await L.takeOverAdminLock(j.page);
  await j.page.check('input[name=ceEnabled]');
  await j.page.check('input[name=ceMode][value=warn]');
  await j.page.fill('textarea[name=ceIncludedSpaces]', 'Warn');
  await j.page.fill('input[name=ceSenderAddress]', 'wiki@example.test');
  await j.page.click('input[type=submit][value=Save]');
  await j.page.waitForLoadState('load'); await j.page.waitForTimeout(1500);
  const body = await j.page.innerText('body');
  L.check(/Settings saved/.test(body), 'an admin without programming rights can save the settings', (body.replace(/\s+/g, ' ').match(/(Nothing was saved|locked by|Only wiki|expired|can't start)[^.]*/i) || [body.replace(/\s+/g, ' ').slice(0, 200)])[0]);
  L.check(/runs at 03:17 as XWiki\.joe/.test(body), 'the job is now set to run as that admin');
  await j.browser.close();

  // Trigger the job from the Scheduler application.
  const { browser, page: p } = await L.open();
  await p.goto(L.BASE + '/bin/view/Scheduler/', { waitUntil: 'load' });
  const row = p.locator('tr', { hasText: 'Content Expiry daily run' });
  L.check(/Normal/.test(await row.innerText()), 'the job is registered and scheduled after import');
  await row.locator('a:has-text("Trigger")').click();
  await p.waitForLoadState('load');
  let state = {};
  for (let i = 0; i < 20; i++) {
    await M.wait(1500);
    state = await L.groovy(`def e = x.parseGroovyFromPage('ContentExpiry.Code.Engine', ctx)
def s = e.summary()
k('lastRun', s.lastRun); k('result', s.lastResult); k('jobUser', s.jobUser)`, 'JobState');
    if (state.lastRun && state.lastRun !== 'null') break;
  }
  L.check(state.lastRun && state.lastRun !== 'null', 'triggering the job completes a run', JSON.stringify(state));
  L.check(/Warned about 5 page\(s\) in 4 email\(s\)/.test(state.result || ''), 'the job did the same work as a direct run', state.result);
  L.check(M.inbox().length === 4, 'the job sent the four digest emails', `${M.inbox().length} emails`);
  L.check(state.jobUser === 'XWiki.joe', 'summary reports who the job runs as', state.jobUser);

  // The report page shows the outcome.
  await L.releaseAdminLock();
  await p.goto(L.adminUrl('contentExpiryReport'), { waitUntil: 'load' });
  await L.takeOverAdminLock(p);
  const rep = await p.innerText('body');
  L.check(/The daily job last finished on .* as XWiki\.joe\. Warned about 5 page/.test(rep.replace(/\s+/g, ' ')), 'the report page shows the last run and its result');
  await p.screenshot({ path: path.join(L.OUT, 'report_after_run.png'), fullPage: true });
  await browser.close();

  // A second trigger finds nothing new.
  const before = M.inbox().length;
  const b2 = await L.open();
  await b2.page.goto(L.BASE + '/bin/view/Scheduler/', { waitUntil: 'load' });
  await b2.page.locator('tr', { hasText: 'Content Expiry daily run' }).locator('a:has-text("Trigger")').click();
  await b2.page.waitForLoadState('load'); await M.wait(6000);
  L.check(M.inbox().length === before, 'triggering the job again sends nothing twice');
  await b2.browser.close();

  for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
  L.done();
})();
