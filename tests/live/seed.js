// Creates pages in space "Old" with backdated modification dates, via a throwaway Groovy page
// (needs programming rights: runs as superadmin). Safe to run repeatedly.
const L = require('./lib');
const PAGES = [['Old.Ancient.WebHome', 80, 'Ancient page'], ['Old.Stale.WebHome', 62, 'Stale page'],
  ['Old.Soon.WebHome', 59, 'Expires soon page'], ['Old.Mid.WebHome', 40, 'Middle aged page'],
  ['Old.Fresh.WebHome', 1, 'Fresh page'], ['Old.Ancient.Child.WebHome', 75, 'Child of ancient']];
const groovy = `{{groovy}}
import java.util.Calendar
def ctx = xcontext.getContext(); def x = ctx.getWiki()
def mk = { String ref, int monthsAgo, String title ->
  def d = x.getDocument(ref, ctx); d.setTitle(title); d.setContent("Seeded test page " + ref)
  def c = Calendar.getInstance(); c.add(Calendar.MONTH, -monthsAgo)
  d.setDate(c.getTime()); d.setCreationDate(c.getTime()); d.setContentUpdateDate(c.getTime())
  d.setCreatorReference(xcontext.getUserReference()); d.setAuthorReference(xcontext.getUserReference())
  d.setContentDirty(false); d.setMetaDataDirty(false)
  x.saveDocument(d, "seed", true, ctx)
  println "SEEDED " + ref + " " + x.getDocument(ref, ctx).getDate()
}
${PAGES.map(([r, m, t]) => `mk("${r}", ${m}, "${t}")`).join('\n')}
{{/groovy}}`;
(async () => {
  const put = await L.putPage('Seed', 'WebHome', 'Seed', groovy);
  L.check([201, 202].includes(put.status), 'seed page written', String(put.status));
  const t = await L.viewText('Seed', 'WebHome');
  const n = (t.match(/SEEDED /g) || []).length;
  L.check(n === PAGES.length, `seeded ${PAGES.length} pages`, `got ${n}`);
  L.done();
})();
