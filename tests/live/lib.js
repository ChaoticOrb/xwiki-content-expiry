// Shared helpers for the live tests. Run with NODE_PATH=$(npm root -g) so `playwright` resolves.
const { chromium } = require('playwright');
const path = require('path');
const BASE = process.env.XWIKI_URL || 'http://localhost:8080/xwiki';
const ADMIN = { user: 'superadmin', pass: process.env.XWIKI_SUPERADMIN_PASSWORD || 'testpass123' };
const OUT = process.env.LIVE_OUT || path.join(__dirname, 'out');
require('fs').mkdirSync(OUT, { recursive: true });

const auth = (u = ADMIN) => 'Basic ' + Buffer.from(u.user + ':' + u.pass).toString('base64');
const adminUrl = (section, extra = '') => `${BASE}/bin/admin/XWiki/XWikiPreferences?editor=globaladmin&section=${section}${extra}`;

async function open(user = ADMIN, viewport = { width: 1280, height: 1600 }) {
  await releaseAdminLock();
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport });
  page.setDefaultTimeout(120000);
  await page.goto(`${BASE}/bin/login/XWiki/XWikiLogin`, { waitUntil: 'domcontentloaded' });
  await page.fill('#j_username', user.user);
  await page.fill('#j_password', user.pass);
  await page.click('input[type=submit]');
  await page.waitForLoadState('domcontentloaded');
  return { browser, page };
}

// Another admin's open session leaves XWiki's edit lock on the global admin page. Takes it over if so.
async function takeOverAdminLock(page) {
  await page.waitForTimeout(1500);
  const force = page.locator('text=Force editing');
  if (await force.count()) { await force.first().click(); await page.waitForLoadState('load'); await page.waitForTimeout(1000); }
}

// XWiki's ConfigurableClass framework locks the page that registers an admin section (ContentExpiry.Code.Admin)
// while an admin has the section open, and shows "locked by ..." to the next admin. That is standard for every
// configurable section. Headless browsers don't release it on close, so tests release it with the cancel action.
async function releaseAdminLock() {
  // cancel only removes the caller's own lock, so cancel as every admin the tests use.
  for (const u of [ADMIN, { user: 'joe', pass: 'joepass1' }]) {
    await fetch(`${BASE}/bin/cancel/ContentExpiry/Code/Admin`, { headers: { Authorization: auth(u) }, redirect: 'manual' }).catch(() => {});
  }
}

// REST helper (basic auth). body may be a string.
async function rest(method, p, body, user = ADMIN, type = 'application/xml') {
  const r = await fetch(`${BASE}/rest/${p}`, { method, headers: { Authorization: auth(user), 'Content-Type': type }, body });
  return { status: r.status, text: await r.text() };
}

// Creates or overwrites a page whose content is the given xwiki/2.1 text.
async function putPage(space, name, title, content, user = ADMIN) {
  const xml = `<page xmlns="http://www.xwiki.org"><title>${title}</title><syntax>xwiki/2.1</syntax><content><![CDATA[${content}]]></content></page>`;
  return rest('PUT', `wikis/xwiki/spaces/${space}/pages/${name}`, xml, user);
}

async function viewText(space, name, user = ADMIN) {
  const r = await fetch(`${BASE}/bin/view/${space}/${name}`, { headers: { Authorization: auth(user) } });
  return (await r.text()).replace(/<script[\s\S]*?<\/script>/g, '').replace(/<[^>]*>/g, '');
}

// Runs a Groovy snippet as superadmin on a throwaway page and returns what it printed with k('name', value)
// as a map, plus .error if the macro failed. Snippet has `ctx`, `x` (the XWiki object) and `k` in scope.
async function groovy(code, name = 'Run') {
  const body = '{{groovy}}\nimport com.xpn.xwiki.web.Utils\n' +
    'def ctx = xcontext.getContext(); def x = ctx.getWiki()\n' +
    "def k = { String key, Object v -> println 'K_' + key + '=' + v + '=K' }\n" + code + '\n{{/groovy}}';
  await putPage('Seed', name, name, body);
  const t = await viewText('Seed', name);
  const out = {};
  for (const m of t.matchAll(/K_([A-Za-z0-9]+)=([\s\S]*?)=K(?=\s*(?:K_|$|[^=]))/g)) out[m[1]] = m[2].trim();
  const err = t.match(/Failed to execute the \[groovy\] macro\. Cause: \[([\s\S]{0,600})/);
  if (err) out.error = err[1];
  return out;
}

let failures = 0;
function check(ok, label, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${!ok && detail ? '  -> ' + detail : ''}`);
  if (!ok) failures++;
}
function done() { console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed'); process.exit(failures ? 1 : 0); }

module.exports = { BASE, ADMIN, OUT, auth, adminUrl, open, takeOverAdminLock, releaseAdminLock, rest, putPage, viewText, groovy, check, done };
