// Warn and delete mode with stepped time (engine.runAt). Policy starts at T0; pages that are already old expire
// at T0 + 60 days (the safety floor), final warning (7 days) goes out on day 54.
const path = require('path');
const L = require('./lib');
const F = require('./fixtures');
const M = require('./mail');

const DAY = 86400000;
const T0 = Date.now();
const day = (n) => T0 + Math.round(n * DAY);
const longDate = (ms) => new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(ms));
const REF = (n) => `Warn.${n}.WebHome`;

const engine = (code) => L.groovy(`def e = x.parseGroovyFromPage('ContentExpiry.Code.Engine', ctx)\n${code}`, 'DelRun');
const runAt = async (n) => {
  const o = await engine(`def r = e.runAt(new Date(${day(n)}L)); k('ran', r.ran); k('pages', r.pages); k('emails', r.emails); k('deleted', r.deleted); k('capped', r.capped); k('message', r.message)`);
  if (o.error) throw new Error('engine error at day ' + n + ': ' + o.error);
  await M.wait(1200);
  return o;
};
const exists = async (names) => {
  const o = await L.groovy(names.map((n, i) => `k('p${i}', x.exists(x.getDocument('${REF(n)}', ctx).getDocumentReference(), ctx))`).join('\n'), 'Exists');
  return Object.fromEntries(names.map((n, i) => [n, o['p' + i] === 'true']));
};
const trash = async (n) => (await L.groovy(`k('n', x.getDeletedDocuments('${REF(n)}', '', ctx).size())`, 'Trash')).n;
const settings = (extra) => engine(`k('s', e.saveSettings([enabled: '1', mode: 'delete', confirmDelete: '1', retentionMonths: '60', warningDays: '60, 30, 7', includedSpaces: 'Warn', senderAddress: 'wiki@example.test', ${extra || ''}]))`);
async function reset() {
  for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
  const logs = await L.rest('GET', 'wikis/xwiki/spaces/ContentExpiry/spaces/Log/pages');
  for (const m of logs.text.matchAll(/<name>([^<]+)<\/name>/g)) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Log/pages/${m[1]}`);
  await F.seedWarnPages();
  M.failMode(false); M.clear();
}
async function logText() {
  const list = await L.rest('GET', 'wikis/xwiki/spaces/ContentExpiry/spaces/Log/pages');
  let out = ''; const names = [];
  for (const m of list.text.matchAll(/<name>([^<]+)<\/name>/g)) {
    names.push(m[1]);
    out += (await L.rest('GET', `wikis/xwiki/spaces/ContentExpiry/spaces/Log/pages/${m[1]}`)).text;
  }
  return { text: out.replace(/&#\d+;|&[a-z]+;/g, ' '), names };
}

(async () => {
  for (const [n, e] of [['alice', 'alice@example.test'], ['bob', 'bob@example.test'], ['carol', ''], ['joe', 'joe@example.test']]) await F.ensureUser(n, e);
  await F.addToAdminGroup('joe');
  await reset();

  // ---- The settings form protects the switch to delete mode.
  {
    const { browser, page: p } = await L.open(L.ADMIN, { width: 1280, height: 2000 });
    await p.goto(L.adminUrl('contentExpiry'), { waitUntil: 'load' });
    await L.takeOverAdminLock(p);
    await p.check('input[name=ceEnabled]');
    await p.check('input[name=ceMode][value=delete]');
    await p.fill('textarea[name=ceIncludedSpaces]', 'Warn');
    await p.fill('input[name=ceSenderAddress]', 'wiki@example.test');
    await p.click('input[type=submit][value=Save]');
    await p.waitForLoadState('load'); await p.waitForTimeout(1500);
    let body = (await p.innerText('body')).replace(/\s+/g, ' ');
    L.check(/Tick the box to confirm/.test(body) && !/Settings saved/.test(body), 'switching to delete mode is refused without the confirmation box');
    const mode = (await engine(`k('m', e.getSettings().mode)`)).m;
    L.check(mode !== 'delete', 'and nothing was saved', mode);
    await p.check('input[name=ceMode][value=delete]');
    await p.check('input[name=ceConfirmDelete]');
    await p.fill('textarea[name=ceIncludedSpaces]', 'Warn');
    await p.fill('input[name=ceSenderAddress]', 'wiki@example.test');
    await p.fill('input[name=ceMaxDeletions]', '50');
    await p.click('input[type=submit][value=Save]');
    await p.waitForLoadState('load'); await p.waitForTimeout(1500);
    body = (await p.innerText('body')).replace(/\s+/g, ' ');
    L.check(/Settings saved/.test(body), 'with the box ticked delete mode saves', body.slice(body.indexOf('Content expiry') + 20, body.indexOf('Content expiry') + 300));
    await p.screenshot({ path: path.join(L.OUT, 'delete_settings.png'), fullPage: true });
    await browser.close();
  }

  // ---- Walk through time.
  let o = await runAt(0);
  L.check(o.deleted === '0' && o.pages === '5', 'day 0: five pages warned at 60 days, nothing deleted', o.message);
  o = await runAt(31);
  L.check(o.deleted === '0', 'day 31: 30 day warning, nothing deleted', o.message);
  o = await runAt(54);
  // Delivered on day 54, so the email must name day 61 (seven days on), not day 60 (the expiry date).
  const final = M.inbox().filter(m => m.text.includes(longDate(day(61)))).length;
  L.check(o.pages === '5' && final === 4, 'day 54: the final warning is delivered and names the real deletion date, seven days on', `${final} emails with ${longDate(day(61))}; ${o.message}`);

  // bob edits his page after the final warning: its expiry moves five years out.
  const edit = await L.rest('PUT', 'wikis/xwiki/spaces/Warn/spaces/BobOld/pages/WebHome', '<page xmlns="http://www.xwiki.org"><content>Edited by bob after the warning</content></page>', { user: 'bob', pass: 'bobpass1' });
  L.check([200, 202].includes(edit.status), 'bob edits his page after the final warning', String(edit.status));

  o = await runAt(58);
  L.check(o.deleted === '0', 'day 58: not expired yet, nothing deleted', o.message);
  M.clear();
  o = await runAt(60.5);
  L.check(o.deleted === '0' && /waiting for their final warning period/.test(o.message), 'day 60.5: expired, but the 7 days after the final warning are not over, so nothing is deleted', o.message);
  let here = await exists(['AliceOld', 'OwnedByBob', 'NoEmail', 'Parent.Child', 'BobOld', 'Fresh', 'Parent']);
  L.check(Object.values(here).every(Boolean), 'every page still exists', JSON.stringify(here));

  // The recycle bin keeps entries between test runs, so count what this run adds.
  const trashBefore = { AliceOld: Number(await trash('AliceOld')), child: Number(await trash('Parent.Child')) };
  o = await runAt(62);
  L.check(o.deleted === '4', 'day 62: four pages are deleted', o.message);
  here = await exists(['AliceOld', 'OwnedByBob', 'NoEmail', 'Parent.Child', 'BobOld', 'Fresh', 'Parent']);
  L.check(!here.AliceOld && !here.OwnedByBob && !here.NoEmail && !here['Parent.Child'], 'the four expired, warned pages are gone', JSON.stringify(here));
  L.check(here.BobOld, 'the page bob edited after the warning survives');
  L.check(here.Fresh, 'a recently edited page survives');
  L.check(here.Parent, 'a page that had child pages was not deleted, and had never been warned');
  L.check(Number(await trash('AliceOld')) === trashBefore.AliceOld + 1 && Number(await trash('Parent.Child')) === trashBefore.child + 1, 'deleted pages are in the recycle bin, one new entry each');

  // Audit log, only for admins.
  const log = await logText();
  L.check(log.names.length >= 1 && ['AliceOld', 'OwnedByBob', 'NoEmail', 'Child'].every(n => log.text.includes(n)), 'the audit log names each deleted page', log.names.join(','));
  L.check(!/Fresh page|Bob old/.test(log.text), 'and only the deleted ones');
  const month = log.names[0];
  const asAlice = await fetch(`${L.BASE}/bin/view/ContentExpiry/Log/${month}`, { headers: { Authorization: L.auth({ user: 'alice', pass: 'alicepass1' }) } });
  const asJoe = await fetch(`${L.BASE}/bin/view/ContentExpiry/Log/${month}`, { headers: { Authorization: L.auth({ user: 'joe', pass: 'joepass1' }) } });
  L.check(asAlice.status === 403 && asJoe.status === 200, 'the audit log is hidden from an ordinary user and open to an admin', `alice ${asAlice.status}, joe ${asJoe.status}`);

  // Restore one page. It comes back old, so it must be warned again and not deleted at once.
  const restored = await L.groovy(`
def ref = 'Warn.OwnedByBob.WebHome'
def entry = x.getDeletedDocuments(ref, '', ctx)[0]
x.restoreFromRecycleBin(x.getDocument(ref, ctx), entry.getId(), 'restore test', ctx)
k('back', x.exists(x.getDocument(ref, ctx).getDocumentReference(), ctx))`, 'Restore');
  L.check(restored.back === 'true', 'an admin can restore a deleted page from the recycle bin', restored.error);
  M.clear();
  o = await runAt(63);
  const mails = M.inbox();
  const bobMail = mails.find(m => m.to === 'bob@example.test'), aliceMail = mails.find(m => m.to === 'alice@example.test');
  L.check(bobMail && /Owned by Bob page/.test(bobMail.text), 'day 63: the restored page is warned about again, to its owner', mails.map(m => m.to).join(' '));
  L.check(aliceMail && /Parent page/.test(aliceMail.text), 'and the parent, now without children, gets its first warning because it was found late');
  L.check(bobMail && bobMail.text.includes(longDate(day(70))), 'a late warning tells them the page goes seven days later', longDate(day(70)));
  L.check(o.deleted === '0' && /late/.test(o.message), 'nothing is deleted that day', o.message);
  here = await exists(['OwnedByBob', 'Parent']);
  L.check(here.OwnedByBob && here.Parent, 'both pages still exist');
  o = await runAt(69.5);
  L.check(o.deleted === '0', 'day 69.5: still inside the waiting period', o.message);
  o = await runAt(70.5);
  L.check(o.deleted === '2', 'day 70.5: both are deleted after their waiting period', o.message);

  // ---- The per-run limit.
  await reset();
  await settings("maxDeletions: '2'");
  await runAt(0); await runAt(31); await runAt(54);
  o = await runAt(62);
  L.check(o.deleted === '2' && o.capped === 'true' && /limit of 2/.test(o.message), 'with a limit of 2, only two pages are deleted in a run', o.message);
  o = await runAt(63);
  L.check(o.deleted === '2' && o.capped === 'true', 'the next run deletes two more', o.message);
  o = await runAt(64);
  L.check(o.deleted === '1' && o.capped === 'false', 'and the last one the run after', o.message);

  // ---- Nobody to warn, and a page that has children.
  await reset();
  await settings("fallbackContact: 'XWiki.carol', exemptPages: 'Warn.Parent.Child.WebHome'");
  await runAt(0); await runAt(31); await runAt(54); await runAt(62);
  here = await exists(['NoEmail', 'Parent', 'Parent.Child', 'AliceOld']);
  L.check(here.NoEmail, 'a page nobody can be emailed about is never deleted', JSON.stringify(here));
  L.check(!here.AliceOld, 'while the other pages still go');
  // The engine and the report use the real clock, so move the policy date back 70 days to make the old pages
  // really expired. (The engine only recomputes it when the settings change.)
  await L.groovy(`
def cls = new org.xwiki.model.reference.DocumentReference('xwiki', ['ContentExpiry', 'Code'], 'StateClass')
def doc = x.getDocument(new org.xwiki.model.reference.DocumentReference('xwiki', ['ContentExpiry', 'Code'], 'State'), ctx).clone()
doc.getXObject(cls, true, ctx).setDateValue('policyDate', new Date(System.currentTimeMillis() - 70L * 86400000L))
x.saveDocument(doc, 'test: policy date 70 days ago', true, ctx)
k('ok', 1)`, 'BackdatePolicy');
  const waiting = await engine(`k('w', e.waitingForAdmin(50).collect { it.fullName + ' : ' + it.reason }.join(' || '))`);
  L.check(/Warn\.NoEmail\.WebHome : Nobody can be emailed/.test(waiting.w || '') && /Warn\.Parent\.WebHome : Has child pages/.test(waiting.w || ''), 'the engine lists both under waiting for an admin', waiting.w || waiting.error);

  // ---- The report shows them.
  await L.releaseAdminLock();
  const rep = await L.open(L.ADMIN, { width: 1280, height: 2200 });
  await rep.page.goto(L.adminUrl('contentExpiryReport'), { waitUntil: 'load' });
  await L.takeOverAdminLock(rep.page);
  const text = (await rep.page.innerText('body')).replace(/\s+/g, ' ');
  L.check(/Waiting for an admin/.test(text) && /Nobody can be emailed about it/.test(text) && /Has child pages\. Nothing deletes it/.test(text), 'the report page has a Waiting for an admin section');
  L.check(/audit log for this month/.test(text), 'and links to the audit log');
  await rep.page.screenshot({ path: path.join(L.OUT, 'delete_report.png'), fullPage: true });
  await rep.browser.close();

  for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
  M.failMode(false);
  L.done();
})();
