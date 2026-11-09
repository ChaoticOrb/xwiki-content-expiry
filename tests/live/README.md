# Live tests

Browser and REST checks against a real XWiki 17.10. They need a throwaway instance: they create pages, a user and settings.

```
tests/live/start-xwiki.sh     # downloads and starts XWiki 17.10 (Jetty + HSQLDB) on :8080, plus a mail capture server on :2525
tests/live/run-all.sh         # builds the XAR, then runs each test below
```

Needs Java 17+, Node 18+ with the `playwright` package (global install is fine) and Chromium. Screenshots go to `tests/live/out/` (ignored by git).

| Script | Checks |
|---|---|
| `install.js` | XAR imports with no errors, both admin sections render |
| `reset.js` | Deletes the Settings and State pages so the run starts from defaults |
| `seed.js` | Creates pages in space `Old` with backdated modification dates |
| `report.js` | Date logic, safety floor, child-page flag and exclusions in the report. Run on default settings, before `settings.js` |
| `settings.js` | Form validation, persistence, author stamping. Leaves non-default settings behind |
| `access.js` | A user without admin rights is refused at the admin section, on the engine page, and by every admin method of the engine |
| `warn.js` | Warn mode end to end: the form, who is emailed, 60/30/7 thresholds stepped through simulated days, no duplicates, catch-up after a gap, retry after a mail outage |
| `pageui.js` | As alice, bob, dave and an anonymous visitor: banner, Keep (history, author, CSRF, rights), the Expiry tab and the owner form |
| `job.js` | The scheduler job as an admin without programming rights: registered, triggered from the Scheduler app, same result as a direct run, report shows it |

`smtp_sink.py` is a minimal SMTP server that writes each message to `tests/live/out/mail/`. Creating a file named `FAIL` there makes it refuse every recipient, which is how the tests simulate an outage. `fixtures.js` creates the users and pages, `mail.js` reads the captured mail, and `lib.js` has the shared helpers, including `groovy()` to run a snippet on the instance as superadmin.

Time is simulated through the engine's `runAt(date)`, so the 60, 30 and 7 day sequence runs in seconds.

Environment: `XWIKI_URL` (default `http://localhost:8080/xwiki`), `XWIKI_SUPERADMIN_PASSWORD` (default `testpass123`). The start script sets that password in `xwiki.cfg`, which is why this is only for throwaway instances.

Not covered yet: importing the XAR as a normal admin with programming rights instead of superadmin, a real SMTP server with authentication or TLS, the job firing on its own at 03:17 (the tests trigger it by hand), 18.x, and performance on a large wiki.
