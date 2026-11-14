// "My pages": ContentExpiry.WebHome lists what the signed-in user would be warned about.
const path = require('path');
const L = require('./lib');
const F = require('./fixtures');
const M = require('./mail');

const USERS = { alice: { user: 'alice', pass: 'alicepass1' }, bob: { user: 'bob', pass: 'bobpass1' }, carol: { user: 'carol', pass: 'carolpass1' } };
const HOME = `${L.BASE}/bin/view/ContentExpiry/`;
const engine = (c) => L.groovy(`def e = x.parseGroovyFromPage('ContentExpiry.Code.Engine', ctx)\n${c}`, 'MineRun');
const info = (ref) => L.groovy(`def d = x.getDocument("${ref}", ctx); k('version', d.getVersion()); k('comment', d.getComment())`, 'MineInfo');

async function rows(user) {
  const { browser, page } = await L.open(user, { width: 1280, height: 1400 });
  await page.goto(HOME, { waitUntil: 'load' });
  const table = await page.$$eval('.ce-mypages tbody tr', r => r.map(x => x.innerText.replace(/\s+/g, ' ').trim()));
  const text = (await page.innerText('.ce-mypages')).replace(/\s+/g, ' ');
  return { browser, page, table, text };
}
const has = (table, name) => table.some(r => r.includes(name));

(async () => {
  for (const [n, e] of [['alice', 'alice@example.test'], ['bob', 'bob@example.test'], ['carol', ''], ['joe', 'joe@example.test']]) await F.ensureUser(n, e);
  await F.addToAdminGroup('joe');
  for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
  await F.seedWarnPages(); M.clear();

  // Before warnings start there is nothing to show.
  let r = await rows(USERS.alice);
  L.check(/not warning anyone on this wiki yet/.test(r.text), 'while warnings are off, the page says nothing is waiting');
  await r.browser.close();

  const setup = await engine(`k('s', e.saveSettings([enabled: '1', mode: 'warn', retentionMonths: '60', warningDays: '60, 30, 7', includedSpaces: 'Warn', senderAddress: 'wiki@example.test']))
k('r', e.runAt(new Date()).message)`);
  L.check(setup.s === '[ok:true]', 'warn mode is on', setup.error || setup.s);

  // alice created three of the pages. One has child pages, so she is not warned about it.
  r = await rows(USERS.alice);
  L.check(has(r.table, 'Alice old page') && has(r.table, 'Child page'), 'alice sees the pages she created that are about to expire', r.table.join(' || '));
  L.check(!has(r.table, 'Bob old page') && !has(r.table, 'Owned by Bob') && !has(r.table, 'Parent page') && !has(r.table, 'Fresh page'), 'and not bob\'s, the one bob owns, the one with child pages, or a fresh one');
  L.check(r.table.some(x => x.includes('Alice old page') && x.includes('You created it')), 'each row says why it is hers');
  await r.page.screenshot({ path: path.join(L.OUT, 'mypages_alice.png'), fullPage: true });

  // Keep from the list.
  const before = await info('Warn.AliceOld.WebHome');
  await r.page.click('tr:has-text("Alice old page") button:has-text("Keep")');
  await r.page.waitForLoadState('load'); await r.page.waitForTimeout(1500);
  const text = (await r.page.innerText('.ce-mypages')).replace(/\s+/g, ' ');
  const after = await info('Warn.AliceOld.WebHome');
  const tableAfter = await r.page.$$eval('.ce-mypages tbody tr', rr => rr.map(x => x.innerText.replace(/\s+/g, ' ').trim()));
  L.check(/Kept\./.test(text), 'Keep from the list confirms');
  L.check(after.version !== before.version && after.comment === 'Kept by Content Expiry', 'and leaves the "Kept by Content Expiry" edit in the page history', `${before.version} -> ${after.version}, ${after.comment}`);
  L.check(!has(tableAfter, 'Alice old page') && has(tableAfter, 'Child page'), 'the kept page leaves the list, the other stays');
  await r.browser.close();

  // A forged Keep without a valid token changes nothing.
  const childBefore = await info('Warn.Parent.Child.WebHome');
  const forged = await (await fetch(HOME, { method: 'POST', headers: { Authorization: L.auth(USERS.alice), 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'ceMyAction=keep&ceRef=Warn.Parent.Child.WebHome' })).text();
  const childAfter = await info('Warn.Parent.Child.WebHome');
  L.check(/session expired/.test(forged) && childBefore.version === childAfter.version, 'a Keep without a valid token is refused', `${childBefore.version} -> ${childAfter.version}`);

  // bob: the page he created and the one he owns although alice created it.
  r = await rows(USERS.bob);
  L.check(has(r.table, 'Bob old page') && has(r.table, 'Owned by Bob'), 'bob sees his own page and the one he owns', r.table.join(' || '));
  L.check(r.table.some(x => x.includes('Owned by Bob') && x.includes('You are its owner')), 'and why', r.table.join(' || '));
  L.check(!has(r.table, 'Alice old') && !has(r.table, 'Child page'), 'but none of alice\'s');
  await r.browser.close();

  // carol cannot be emailed, so her page falls back to the fallback contact and is not on her list.
  r = await rows(USERS.carol);
  L.check(/Nothing is waiting for you/.test(r.text) && !has(r.table, 'No email page'), 'a user who cannot be emailed does not get pages she would not be warned about', r.text.slice(0, 200));
  await r.browser.close();

  // A visitor who is not signed in.
  const anon = (await (await fetch(HOME)).text()).replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ');
  L.check(/Sign in to see your pages/.test(anon), 'a visitor who is not signed in is asked to sign in');

  // Admins get the shortcuts.
  const adm = await L.open(L.ADMIN);
  await adm.page.goto(HOME, { waitUntil: 'load' });
  L.check(/Administrators: settings and report/.test((await adm.page.innerText('.ce-mypages')).replace(/\s+/g, ' ')), 'an administrator sees links to the settings and the report');
  await adm.browser.close();

  for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
  L.done();
})();
