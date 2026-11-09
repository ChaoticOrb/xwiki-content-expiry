// Helpers for reading what the SMTP sink captured (see smtp_sink.py).
const fs = require('fs');
const path = require('path');
const L = require('./lib');
const DIR = path.join(L.OUT, 'mail');
fs.mkdirSync(DIR, { recursive: true });

function clear() { for (const f of fs.readdirSync(DIR)) fs.unlinkSync(path.join(DIR, f)); }
function failMode(on) { const f = path.join(DIR, 'FAIL'); if (on) fs.writeFileSync(f, ''); else if (fs.existsSync(f)) fs.unlinkSync(f); }
// Messages captured so far: [{to, subject, text}] where text is the plain and HTML parts joined.
function inbox() {
  return fs.readdirSync(DIR).filter(f => f.endsWith('.eml')).sort().map(f => {
    const t = fs.readFileSync(path.join(DIR, f), 'utf8');
    return { file: f, to: (t.match(/X-Envelope-To: (.*)/) || [])[1], subject: (t.match(/Subject: ([^\r\n]*(?:\r?\n[ \t][^\r\n]*)*)/) || [])[1].replace(/\r?\n[ \t]+/g, ' '), text: t };
  });
}
const wait = ms => new Promise(r => setTimeout(r, ms));
module.exports = { DIR, clear, failMode, inbox, wait };
