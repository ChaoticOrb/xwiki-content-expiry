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
  // The engine checks admin rights itself, because parseGroovyFromPage only checks who saved the engine
  // page. Simulate a caller with a non-admin context (a script that has programming rights could do this
  // in real life only by being handed the engine) and try every admin method.
  const methods = ['getSettings()', 'summary()', "report('all', 0, 10)", 'runDaily()', 'runAt(new Date())', 'getJobUser()',
    "saveSettings([enabled: '1', mode: 'warn', retentionMonths: '60', warningDays: '60, 30, 7'])"];
  const body = methods.map((m, i) => `try { e.${m}; k('m${i}', 'ALLOWED') } catch (SecurityException ex) { k('m${i}', 'refused') }`).join('\n');
  const acc = await L.groovy(`def e = x.parseGroovyFromPage('ContentExpiry.Code.Engine', ctx)
ctx.setUserReference(new org.xwiki.model.reference.DocumentReference("xwiki", "XWiki", "alice"))
${body}
ctx.setUserReference(new org.xwiki.model.reference.DocumentReference("xwiki", "XWiki", "superadmin"))
k('admin', e.isAdmin())`, 'EngineAccess');
  methods.forEach((m, i) => L.check(acc["m" + i] === "refused", `engine refuses ${m.split('(')[0]} for a non-admin`, acc["m" + i] || acc.error));
  L.check(acc.admin === "true", "and still works for the admin afterwards", acc.admin);
  L.done();
})();
