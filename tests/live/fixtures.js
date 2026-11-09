// Shared test data: users with and without email addresses, an admin group member, and pages
// in space "Warn" with backdated modification dates and chosen creators.
const L = require('./lib');

async function ensureUser(name, email, password = name + 'pass1') {
  await L.rest('PUT', `wikis/xwiki/spaces/XWiki/pages/${name}`, `<page xmlns="http://www.xwiki.org"><title>${name}</title><content></content></page>`);
  // Replace any earlier XWikiUsers object so the email is always what the test expects.
  const list = await L.rest('GET', `wikis/xwiki/spaces/XWiki/pages/${name}/objects/XWiki.XWikiUsers`);
  for (const m of list.text.matchAll(/<objectSummary>[\s\S]*?<number>(\d+)<\/number>/g)) {
    await L.rest('DELETE', `wikis/xwiki/spaces/XWiki/pages/${name}/objects/XWiki.XWikiUsers/${m[1]}`);
  }
  const props = { first_name: name[0].toUpperCase() + name.slice(1), last_name: 'Tester', password, active: 1, email: email || '' };
  const xml = '<object xmlns="http://www.xwiki.org"><className>XWiki.XWikiUsers</className>' +
    Object.entries(props).map(([k, v]) => `<property name="${k}"><value>${v}</value></property>`).join('') + '</object>';
  return L.rest('POST', `wikis/xwiki/spaces/XWiki/pages/${name}/objects`, xml);
}

async function addToAdminGroup(name) {
  const r = await L.rest('GET', 'wikis/xwiki/spaces/XWiki/pages/XWikiAdminGroup/objects/XWiki.XWikiGroups');
  if (r.text.includes(`XWiki.${name}`)) return;
  const xml = `<object xmlns="http://www.xwiki.org"><className>XWiki.XWikiGroups</className><property name="member"><value>XWiki.${name}</value></property></object>`;
  return L.rest('POST', 'wikis/xwiki/spaces/XWiki/pages/XWikiAdminGroup/objects', xml);
}

// [reference, months since last change, creator, title, owner or '']
const WARN_PAGES = [
  ['Warn.AliceOld.WebHome', 80, 'alice', 'Alice old page', ''],
  ['Warn.BobOld.WebHome', 70, 'bob', 'Bob old page', ''],
  ['Warn.OwnedByBob.WebHome', 62, 'alice', 'Owned by Bob page', 'bob'],
  ['Warn.NoEmail.WebHome', 66, 'carol', 'No email page', ''],
  ['Warn.Fresh.WebHome', 1, 'alice', 'Fresh page', ''],
  ['Warn.Parent.WebHome', 65, 'alice', 'Parent page', ''],
  ['Warn.Parent.Child.WebHome', 64, 'alice', 'Child page', ''],
];

async function seedWarnPages() {
  // Pages other tests add to the Warn space would change who is warned about what.
  await L.groovy(`def d = x.getDocument("Warn.Locked.WebHome", ctx); if (!d.isNew()) { x.deleteDocument(d, ctx) }; k('ok', 1)`, 'DropLocked');
  const rows = WARN_PAGES.map(([r, m, c, t, o]) => `mk("${r}", ${m}, "${c}", "${t}", "${o}")`).join('\n');
  return L.groovy(`
import java.util.Calendar
def mk = { String ref, int monthsAgo, String creator, String title, String owner ->
  def d = x.getDocument(ref, ctx)
  // Start clean: earlier tests may have added or changed an owner.
  def ownerClass = new org.xwiki.model.reference.DocumentReference("xwiki", ["ContentExpiry", "Code"], "OwnerClass")
  d.removeXObjects(ownerClass)
  d.setTitle(title); d.setContent("Seeded warn test page")
  def c = Calendar.getInstance(); c.add(Calendar.MONTH, -monthsAgo)
  d.setDate(c.getTime()); d.setCreationDate(c.getTime()); d.setContentUpdateDate(c.getTime())
  def u = new org.xwiki.model.reference.DocumentReference("xwiki", "XWiki", creator)
  d.setCreatorReference(u); d.setAuthorReference(u); d.setContentAuthorReference(u)
  if (owner) {
    def o = d.getXObject(ownerClass, true, ctx)
    o.setStringValue("owner", "XWiki." + owner)
  }
  d.setContentDirty(false); d.setMetaDataDirty(false)
  x.saveDocument(d, "seed", true, ctx)
}
${rows}
k('seeded', ${WARN_PAGES.length})
`, 'SeedWarn');
}

module.exports = { ensureUser, addToAdminGroup, seedWarnPages, WARN_PAGES };
