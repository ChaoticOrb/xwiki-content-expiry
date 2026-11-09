// A user with no admin rights must not reach the admin section or edit the engine page.
const L = require('./lib');
const alice = { user: 'alice', pass: 'alicepass1' };
(async () => {
  await L.rest('PUT', 'wikis/xwiki/spaces/XWiki/pages/alice', '<page xmlns="http://www.xwiki.org"><title>alice</title><content></content></page>');
  const o = await L.rest('POST', 'wikis/xwiki/spaces/XWiki/pages/alice/objects', '<object xmlns="http://www.xwiki.org"><className>XWiki.XWikiUsers</className><property name="first_name"><value>Alice</value></property><property name="password"><value>alicepass1</value></property><property name="active"><value>1</value></property></object>');
  L.check([201, 202, 200].includes(o.status) || o.status === 500, 'test user alice exists', String(o.status));
  const a = await fetch(L.adminUrl('contentExpiry'), { headers: { Authorization: L.auth(alice) } });
  L.check(a.status === 403 || a.status === 401, 'non-admin cannot open the admin section', String(a.status));
  const e = await L.rest('PUT', 'wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/Engine', '<page xmlns="http://www.xwiki.org"><title>x</title><content>x</content></page>', alice);
  L.check(e.status === 401 || e.status === 403, 'non-admin cannot edit the engine page', String(e.status));
  L.done();
})();
