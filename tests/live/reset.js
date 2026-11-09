// Deletes the (never shipped) Settings and State pages so the next run starts from defaults.
const L = require('./lib');
(async () => {
  for (const pg of ['Settings', 'State']) {
    const r = await L.rest('DELETE', `wikis/xwiki/spaces/ContentExpiry/spaces/Code/pages/${pg}`);
    L.check([204, 404].includes(r.status), `${pg} page cleared`, String(r.status));
  }
  L.done();
})();
