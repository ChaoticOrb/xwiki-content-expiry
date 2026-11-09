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

let failures = 0;
function check(ok, label, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${!ok && detail ? '  -> ' + detail : ''}`);
  if (!ok) failures++;
}
function done() { console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed'); process.exit(failures ? 1 : 0); }

module.exports = { BASE, ADMIN, OUT, auth, adminUrl, open, rest, putPage, viewText, check, done };
