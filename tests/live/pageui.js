// What ordinary users see: the banner, the Keep button, the Expiry tab and the owner form.
// Runs as alice and bob (no admin rights) and as an anonymous visitor.
const path = require('path');
const L = require('./lib');
const F = require('./fixtures');
const M = require('./mail');

const ALICE = { user: 'alice', pass: 'alicepass1' };
const view = (space) => `${L.BASE}/bin/view/Warn/${space}/`;
const bannerOf = (page) => page.$$eval('.ce-banner', e => e.map(x => x.innerText.replace(/\s+/g, ' ').trim()).join(' || '));
const info = (ref) => L.groovy(`
def d = x.getDocument("${ref}", ctx)
k('version', d.getVersion()); k('comment', d.getComment()); k('minor', d.isMinorEdit())
k('author', d.getAuthorReference()); k('contentAuthor', d.getContentAuthorReference())
k('ageSeconds', (System.currentTimeMillis() - d.getDate().getTime()).intdiv(1000))
def o = d.getXObject(new org.xwiki.model.reference.DocumentReference("xwiki", ["ContentExpiry", "Code"], "OwnerClass"))
k('owner', o == null ? 'none' : o.getStringValue('owner'))
`, 'Info');

(async () => {
  for (const [n, e] of [['alice', 'alice@example.test'], ['bob', 'bob@example.test'], ['carol', ''], ['joe', 'joe@example.test']]) await F.ensureUser(n, e);
  await F.addToAdminGroup('joe');
  await F.ensureUser('dave', 'dave@example.test');
  await F.seedWarnPages();
  // A page dave may view but not edit.
  await L.groovy(`
import java.util.Calendar
def d = x.getDocument("Warn.Locked.WebHome", ctx)
d.setTitle("Locked page"); d.setContent("Locked")
def c = Calendar.getInstance(); c.add(Calendar.MONTH, -63)
d.setDate(c.getTime()); d.setCreationDate(c.getTime()); d.setContentUpdateDate(c.getTime())
def u = new org.xwiki.model.reference.DocumentReference("xwiki", "XWiki", "alice")
d.setCreatorReference(u); d.setAuthorReference(u); d.setContentAuthorReference(u)
def r = d.getXObject(new org.xwiki.model.reference.DocumentReference("xwiki", ["XWiki"], "XWikiRights"), true, ctx)
r.setStringValue("users", "XWiki.dave"); r.setStringValue("levels", "edit"); r.setIntValue("allow", 0)
d.setContentDirty(false); d.setMetaDataDirty(false)
x.saveDocument(d, "seed", true, ctx)
k('ok', 1)`, 'SeedLocked');
  for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
  M.clear();
  const setup = await L.groovy(`
def e = x.parseGroovyFromPage('ContentExpiry.Code.Engine', ctx)
k('s', e.saveSettings([enabled: '1', mode: 'warn', retentionMonths: '60', warningDays: '60, 30, 7', includedSpaces: 'Warn', senderAddress: 'wiki@example.test']))
k('r', e.runAt(new Date()).message)`, 'Setup');
  L.check(setup.s === '[ok:true]', 'warn mode is on for the UI tests', setup.error || setup.s);

  const { browser, page: p } = await L.open(ALICE, { width: 1280, height: 1100 });

  // Banner on a page inside its warning window.
  await p.goto(view('BobOld'), { waitUntil: 'load' });
  let banner = await bannerOf(p);
  L.check(/reaches its expiry date on 8 January 2027 \(in 60 days\)/.test(banner) && /Keep this page/.test(banner), 'a page in its warning window shows the banner and Keep button', banner);
  L.check(/warn only, so nothing is deleted yet/.test(banner), 'the banner says nothing is deleted in warn mode');
  await p.screenshot({ path: path.join(L.OUT, 'ui_banner.png') });

  // No banner where it does not apply.
  for (const [name, why] of [['Fresh', 'recently edited'], ['Parent', 'has child pages']]) {
    await p.goto(view(name), { waitUntil: 'load' });
    L.check((await bannerOf(p)) === '', `no banner on a page that is ${why}`);
  }
  await p.goto(`${L.BASE}/bin/view/Main/`, { waitUntil: 'load' });
  L.check((await bannerOf(p)) === '', 'no banner on a page outside the covered spaces');

  // Keep without a valid token is refused and changes nothing.
  const before = await info('Warn.AliceOld.WebHome');
  const bad = await fetch(view('AliceOld'), { method: 'POST', headers: { Authorization: L.auth(ALICE), 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'ceAction=keep' });
  const badText = await bad.text();
  const afterBad = await info('Warn.AliceOld.WebHome');
  L.check(/session expired/.test(badText) && afterBad.version === before.version, 'Keep without a valid CSRF token is refused and the page is untouched', `version ${before.version} -> ${afterBad.version}`);

  // Anonymous visitors see the banner but cannot keep.
  const anon = (await (await fetch(view('AliceOld'))).text());
  L.check(/reaches its expiry date/.test(anon) && /Sign in with edit rights/.test(anon) && !/Keep this page<\/button>/.test(anon), 'a visitor who is not signed in sees the banner without a Keep button');

  // Keep as alice a page that bob last edited.
  const bobBefore = await info('Warn.BobOld.WebHome');
  await p.goto(view('BobOld'), { waitUntil: 'load' });
  await p.click('.ce-banner button:has-text("Keep this page")');
  await p.waitForLoadState('load'); await p.waitForTimeout(1500);
  const msg = await p.$$eval('.ce-message', e => e.map(x => x.innerText).join(' '));
  L.check(/Kept\./.test(msg), 'clicking Keep confirms on the page', msg);
  L.check((await bannerOf(p)) === '', 'the banner is gone once the page is kept');
  const kept = await info('Warn.BobOld.WebHome');
  L.check(kept.version !== bobBefore.version && kept.comment === 'Kept by Content Expiry' && kept.minor === 'true', 'Keep leaves a minor edit with the comment "Kept by Content Expiry"', JSON.stringify(kept));
  L.check(kept.author === 'xwiki:XWiki.alice' && kept.contentAuthor === bobBefore.contentAuthor, 'Keep records who clicked as author without changing the content author', `author ${kept.author}, content author ${bobBefore.contentAuthor} -> ${kept.contentAuthor}`);
  L.check(Number(kept.ageSeconds) < 120, 'Keep restarts the clock');

  // The Expiry tab and the owner form.
  await p.goto(view('NoEmail'), { waitUntil: 'load' });
  await p.click('#docExtrasTabsUl >> text=Expiry');
  await p.waitForSelector('.ce-tab');
  let tab = await p.innerText('.ce-tab');
  L.check(/expires on .*8 January 2027/.test(tab) && /Warnings for this page go to/.test(tab) && /fallback contact/.test(tab), 'the Expiry tab shows when the page expires and who is warned', tab.replace(/\s+/g, ' ').slice(0, 200));
  await p.screenshot({ path: path.join(L.OUT, 'ui_tab.png'), fullPage: true });

  await p.fill('#ceOwner', 'nobody');
  await p.click('.ce-owner-form input[type=submit]');
  await p.waitForLoadState('load'); await p.waitForTimeout(1500);
  L.check(/can't be the owner/.test(await p.$$eval('.ce-message', e => e.map(x => x.innerText).join(' '))), 'an owner who is not a user is rejected with a message');
  L.check((await info('Warn.NoEmail.WebHome')).owner === 'none', 'a rejected owner changes nothing');

  await p.fill('#ceOwner', 'bob');
  await p.click('.ce-owner-form input[type=submit]');
  await p.waitForLoadState('load'); await p.waitForTimeout(1500);
  L.check(/Owner saved/.test(await p.$$eval('.ce-message', e => e.map(x => x.innerText).join(' '))), 'saving an owner confirms on the page');
  const owned = await info('Warn.NoEmail.WebHome');
  L.check(owned.owner === 'XWiki.bob' && owned.author === 'xwiki:XWiki.alice', 'the owner is stored on the page and the save is attributed to the person who made it', JSON.stringify(owned));
  L.check(await p.isVisible('.ce-tab'), 'the Expiry tab reopens after saving');
  tab = await p.innerText('.ce-tab');
  L.check(/go to\s+XWiki\.bob\s+\(the page's owner\)/.test(tab), 'warnings now go to the owner', tab.replace(/\s+/g, ' ').slice(0, 220));

  await p.fill('#ceOwner', '');
  await p.click('.ce-owner-form input[type=submit]');
  await p.waitForLoadState('load'); await p.waitForTimeout(1500);
  L.check((await info('Warn.NoEmail.WebHome')).owner === 'none', 'clearing the owner removes it');
  await browser.close();

  // dave can view Warn.Locked but a deny rule stops him editing it.
  {
    const dave = await L.open({ user: 'dave', pass: 'davepass1' }, { width: 1280, height: 1100 });
    const q = dave.page;
    await q.goto(view('Locked'), { waitUntil: 'load' });
    const b = await bannerOf(q);
    L.check(/expiry date/.test(b) && /Sign in with edit rights/.test(b) && !(await q.$('.ce-banner button')), 'a user without edit right sees the banner but no Keep button', b);
    await q.click('#docExtrasTabsUl >> text=Expiry');
    await q.waitForSelector('.ce-tab');
    L.check(!(await q.$('.ce-owner-form')) && !/Warnings for this page go to/.test(await q.innerText('.ce-tab')), 'the Expiry tab gives them no owner form and does not say who is emailed');
    const token = await q.evaluate(() => document.querySelector('meta[name=form_token]').content);
    const lockedBefore = await info('Warn.Locked.WebHome');
    const post = (body) => q.evaluate(async ([url, b]) => (await (await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: b })).text()), [view('Locked'), body]);
    const keepTry = await post('ceAction=keep&form_token=' + encodeURIComponent(token));
    L.check(/You need edit rights on this page to keep it/.test(keepTry), 'a forged Keep with a valid token is still refused without edit right');
    const ownerTry = await post('ceAction=setOwner&ceOwner=bob&form_token=' + encodeURIComponent(token));
    L.check(/You need edit rights on this page to change its owner/.test(ownerTry), 'a forged owner change is refused without edit right');
    const lockedAfter = await info('Warn.Locked.WebHome');
    L.check(lockedBefore.version === lockedAfter.version && lockedAfter.owner === 'none', 'the locked page was not changed by either attempt', `${lockedBefore.version} -> ${lockedAfter.version}, owner ${lockedAfter.owner}`);
    await dave.browser.close();
  }

  for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
  L.done();
})();
