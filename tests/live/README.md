# Live tests

Browser and REST checks against a real XWiki 17.10. They need a throwaway instance: they create pages, a user and settings.

```
tests/live/start-xwiki.sh     # downloads and starts XWiki 17.10 (Jetty + HSQLDB) on :8080, first start takes a minute or two
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
| `access.js` | A user without admin rights is refused |

Environment: `XWIKI_URL` (default `http://localhost:8080/xwiki`), `XWIKI_SUPERADMIN_PASSWORD` (default `testpass123`). The start script sets that password in `xwiki.cfg`, which is why this is only for throwaway instances.

Not covered yet: the engine's own admin check (XWiki's script rights stop a non-admin earlier), and a normal admin account with programming rights instead of superadmin.
