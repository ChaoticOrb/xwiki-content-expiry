# Content Expiry for XWiki

Deletes pages that nobody has changed for a set time (5 years by default), after warning their owners.
Admins configure it in Administration. Owners get emails and a one-click Keep button.

**Status: 0.2, warn only.** It lists what would expire, emails page owners before the expiry date, and shows a banner with a Keep button on pages that are close to it. It deletes nothing yet: deletion comes in 0.3. See [docs/design.md](docs/design.md) for the plan.

Built entirely from wiki pages (Velocity, plus a Groovy engine). No Java to compile.

## Requirements

- Written for XWiki 17.10 LTS and 18.x. Older versions may work but haven't been checked. Tested on XWiki 17.10.14 (standalone Jetty and HSQLDB) with the suite in `tests/live`. 18.x hasn't been run. The XAR was always imported by the superadmin account, never by a normal admin with programming rights.
- An admin account **with programming rights** to install it. The engine and the UI extensions run code, and XWiki only runs that from pages saved by such a user.
- Outgoing mail working in XWiki (Administration > Mail), for warn mode.

## Install

1. Get the XAR: build it (below) or download it from a release.
2. Log in as an admin with programming rights.
3. Go to **Administration > Content > Import**, upload the XAR, select all pages and import.
4. Go to **Administration > Content > Content expiry**. If you see an error saying the engine can't start, open `ContentExpiry.Code.Engine`, click Edit and save it without changes. XWiki may record the importing user, or the author in the file, as the page author depending on version and import options; the engine needs its page saved by someone with programming rights.

The import also adds space rights so only wiki admins can edit pages in the `ContentExpiry` space.

### Upgrading

Import the new XAR over the old one. Your settings are on `ContentExpiry.Code.Settings` and the engine's bookkeeping is on `ContentExpiry.Code.State`. Neither ships in the XAR, so an import can't overwrite them.

## Use

- **Administration > Content > Content expiry**: turn it on, pick the mode, and set the retention period, warning days, which spaces it covers, which pages are exempt, the fallback contact and the sender address.
- **Administration > Content > Content expiry report**: counts, when the daily job last ran and what it did, and a table of pages with when they were last changed, when they expire and who is warned.
- **Under every covered page**: a banner with a **Keep this page** button once the page is inside its warning window, and an **Expiry** tab where anyone who can edit the page can set its owner.

Go-live is meant to be: install, leave it in report-only mode for a few weeks and read the report, adjust exclusions, then switch to warn only.

### Warnings

In warn mode a scheduled job (Administration > Scheduler, "Content Expiry daily run", 03:17) emails owners when a page passes a warning threshold, 60, 30 and 7 days before expiry by default. Each person gets one email per run listing all their pages. A missed day is caught up on the next run and nothing is sent twice. An email that fails (mail server down, bad address) is retried on each later run for 14 days.

Who is warned: the page's owner (Expiry tab), else its creator, else its last editor, skipping anyone who is disabled or has no email address; if none of them can be emailed, the fallback contact (a user, a group or an address). Pages with child pages and excluded pages are not warned about.

The job runs as the admin who last saved the settings, and stops working if that account loses admin rights. The scheduler fixes that user at the moment a job is scheduled, and an import schedules it with a placeholder (`XWiki.Admin`), so **after every import, open Content expiry and click Save** to point the job at yourself. The report warns when the job's user is not an administrator, and shows why the last run failed. If warnings stop, check the report first, then Administration > Scheduler.

**Keep** saves the page as a minor edit with the comment "Kept by Content Expiry", which restarts the clock and leaves a line in the page history. It records the person who clicked as the page's author and leaves the content author alone.

The clock is the page's last-modified date, so any save restarts it. Nothing can expire until the longest warning period has passed after warnings are switched on, so every owner gets every warning even on the first run.

Always excluded, whatever the settings: hidden pages; the `XWiki`, `Scheduler`, `Panels` and `ContentExpiry` spaces; `Main.WebHome`; user and group profiles; pages holding application code (UI extensions, macros, admin sections, scheduler jobs, skin extensions, components, template providers, skins, blog categories); class definitions; and pages that belong to an installed extension. Pages with child pages are listed but will never be deleted automatically.

## Develop

```
python3 build.py                  # builds dist/content-expiry-<version>.xar
python3 build.py --check          # validates without writing dist/
python3 tests/run_policy_tests.py # unit tests for the date logic (needs a JDK)
tests/live/start-xwiki.sh         # throwaway XWiki 17.10 plus a mail capture server
tests/live/run-all.sh             # builds, then runs the live tests against it
```

The live tests are described in [tests/live/README.md](tests/live/README.md).

Python 3.11+ standard library only.

### Layout

```
src/package.json            XAR name, version and extension id
src/pages/<Space>/<Page>.xml  one file per wiki page, XWiki's own page XML format
src/pages/.../*.vm|.groovy|.less  code pulled into pages by file="..."
src/classdefs/              definitions of XWiki's built-in classes our objects use
tools/extract_classdef.py   copies a class definition out of an exported page
tests/                      Policy tests (javac)
docs/design.md              design spec and roadmap
```

Page sources are normal XWiki page XML with three shortcuts, so they stay short and readable: header fields are optional, any element can take its text from a file with `file="..."`, and objects can leave out `<name>`, `<number>`, `<guid>` and `<class>`. `build.py` fills all of that in. A page exported from XWiki can be dropped in as it is.

### Pages

| Page | What it is |
|---|---|
| `ContentExpiry.Code.Engine` | Groovy engine: settings, queries, report, daily run, email, Keep, owner, policy date logic |
| `ContentExpiry.Code.Admin` | Registers the two admin sections; holds the stylesheet |
| `ContentExpiry.Code.AdminSettingsSheet` / `AdminReportSheet` | Velocity for the two sections |
| `ContentExpiry.Code.Macros` | Shared Velocity macros |
| `ContentExpiry.Code.ConfigClass` / `StateClass` / `OwnerClass` | Classes for settings, engine bookkeeping and the per-page owner |
| `ContentExpiry.Code.PageUI` | UI extensions: the banner and actions under the page title, and the Expiry tab |
| `ContentExpiry.Code.Job` | The daily scheduler job |
| `ContentExpiry.Code.MailTemplate` | The warning email (an `XWiki.Mail` object) |
| `ContentExpiry.Code.Translations` | Admin section names |
| `ContentExpiry.WebPreferences` | Space rights: only admins can edit |

### Rules for the engine

- Every public method in the engine checks rights itself. `parseGroovyFromPage` only checks who saved the engine page, not who is calling it. Admin methods need wiki admin rights; `bannerFor`, `pageInfo`, `keep` and `setOwner` are for ordinary users and check that user's own view or edit right on the page.
- Pages the engine saves for a user (Keep, owner) change the author but never the content author, so a page's scripts don't run with a different person's rights.
- The `Policy` class inside the engine must stay valid Java as well as Groovy, so the tests can compile it with `javac`. The test runner rejects `$`, single-quoted literals and `/` division in it.

## Licence

MIT. See [LICENSE](LICENSE).
