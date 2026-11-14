// The warning email: an admin without programming rights makes a copy, edits it, sends themselves a sample;
// an import of a new version leaves the copy alone; resetting goes back to the shipped one.
const { execSync } = require('child_process');
const path = require('path');
const L = require('./lib');
const F = require('./fixtures');
const M = require('./mail');

const JOE = { user: 'joe', pass: 'joepass1' };
const CUSTOM = 'wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/MailTemplateCustom';
const engine = (c) => L.groovy(`def e = x.parseGroovyFromPage('ContentExpiry.Code.Engine', ctx)\n${c}`, 'TplRun');

async function click(label, expect) {
  await L.releaseAdminLock();
  const { browser, page } = await L.open(JOE, { width: 1280, height: 2200 });
  await page.goto(L.adminUrl('contentExpiry'), { waitUntil: 'load' });
  await L.takeOverAdminLock(page);
  await page.click(`button:has-text("${label}")`);
  await page.waitForLoadState('load'); await page.waitForTimeout(2500);
  const text = (await page.innerText('body')).replace(/\s+/g, ' ');
  await page.screenshot({ path: path.join(L.OUT, 'template_' + label.replace(/\W+/g, '_') + '.png'), fullPage: true });
  await browser.close();
  return text;
}

(async () => {
  for (const [n, e] of [['alice', 'alice@example.test'], ['bob', 'bob@example.test'], ['carol', ''], ['joe', 'joe@example.test']]) await F.ensureUser(n, e);
  await F.addToAdminGroup('joe');
  await L.rest('DELETE', CUSTOM);
  for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
  M.failMode(false); M.clear();
  const s = await engine(`k('s', e.saveSettings([enabled: '1', mode: 'warn', retentionMonths: '60', warningDays: '60, 30, 7', includedSpaces: 'Warn', senderAddress: 'wiki@example.test']))`);
  L.check(s.s === '[ok:true]', 'settings saved', s.error || s.s);

  // The shipped email.
  let text = await click('Send me a sample email');
  L.check(/A sample email is on its way to joe@example\.test/.test(text), 'the sample button reports where it went', text.slice(text.indexOf('Warning email'), text.indexOf('Warning email') + 200));
  await M.wait(1500);
  let box = M.inbox();
  L.check(box.length === 1 && box[0].to === 'joe@example.test', 'the sample arrives at the admin\'s own address', box.map(m => m.to).join(' '));
  L.check(/Sample page: Team handbook/.test(box[0].text) && /Sample page: Old project plan/.test(box[0].text), 'and contains made-up pages, not real ones');
  L.check(/3 pages on .* are due to expire/.test(box[0].subject) && !/\$/.test(box[0].subject), 'with the shipped subject, rendered', box[0].subject);
  L.check(/\/bin\/view\/ContentExpiry\//.test(box[0].text), 'and a link to My pages');

  // Make a copy as an admin without programming rights, and edit its subject.
  text = await click('Make my own copy to edit');
  L.check(/Made your own copy/.test(text), 'joe makes his own copy of the email');
  const meta = await L.rest('GET', CUSTOM, undefined, JOE);
  L.check(meta.status === 200 && /<author>XWiki\.joe/.test(meta.text), 'the copy exists and is authored by joe', `${meta.status} ${(meta.text.match(/<author>[^<]*/) || [''])[0]}`);
  const edit = await L.rest('PUT', `${CUSTOM}/objects/XWiki.Mail/0/properties/subject`, '<property xmlns="http://www.xwiki.org" name="subject"><value>CUSTOM: $count pages on $wikiName need a look</value></property>', JOE);
  L.check([200, 202].includes(edit.status), 'joe edits the subject in the copy', String(edit.status));
  M.clear();
  text = await click('Send me a sample email');
  await M.wait(1500); box = M.inbox();
  L.check(box.length === 1 && /^CUSTOM: 3 pages on .* need a look$/.test(box[0].subject), 'the edited subject is used, and its variables are filled in', box.map(m => m.subject).join(' | ') + ' | ' + text.slice(text.indexOf('Warning email'), text.indexOf('Warning email') + 200));

  // Importing a new version leaves the copy alone.
  execSync('node install.js', { cwd: __dirname, stdio: 'ignore', env: process.env });
  const still = await L.rest('GET', CUSTOM, undefined, JOE);
  L.check(still.status === 200, 'after importing the XAR again the copy is still there', String(still.status));
  M.clear();
  text = await click('Send me a sample email');
  await M.wait(1500); box = M.inbox();
  L.check(box.length === 1 && /^CUSTOM: /.test(box[0].subject), 'and still used', box.map(m => m.subject).join(' | '));

  // Back to the shipped email.
  text = await click('Go back to the shipped email');
  L.check(/Back to the shipped email/.test(text), 'resetting says it went back');
  const gone = await L.rest('GET', CUSTOM, undefined, JOE);
  L.check(gone.status === 404, 'the copy is gone', String(gone.status));
  M.clear();
  text = await click('Send me a sample email');
  await M.wait(1500); box = M.inbox();
  L.check(box.length === 1 && !/CUSTOM/.test(box[0].subject) && /pages on .* are due to expire/.test(box[0].subject), 'the shipped subject is back', box.map(m => m.subject).join(' | '));

  // The engine refuses all of this to ordinary users.
  const refused = await L.groovy(`def e = x.parseGroovyFromPage('ContentExpiry.Code.Engine', ctx)
ctx.setUserReference(new org.xwiki.model.reference.DocumentReference("xwiki", "XWiki", "alice"))
['customizeTemplate()', 'resetTemplate()', 'sendSample()', 'isTemplateCustomized()'].eachWithIndex { m, i ->
  try { Eval.me('e', e, 'e.' + m); k('m' + i, 'ALLOWED') } catch (SecurityException ex) { k('m' + i, 'refused') } catch (Throwable t) { k('m' + i, 'refused') } }
ctx.setUserReference(new org.xwiki.model.reference.DocumentReference("xwiki", "XWiki", "superadmin"))`, 'TplAccess');
  ['customizeTemplate', 'resetTemplate', 'sendSample', 'isTemplateCustomized'].forEach((m, i) => L.check(refused['m' + i] === 'refused', `${m} is refused for a user who is not an admin`, refused['m' + i] || refused.error));

  for (const pg of ['Settings', 'State']) await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
  L.done();
})();
