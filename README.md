# Content Expiry for XWiki

Deletes pages that nobody has changed for a set time (5 years by default), after warning their owners.
Admins configure it in Administration. Owners will get emails and a one-click Keep button.

**Status: 0.1, report only.** This version lists what would expire and who would be warned. It sends no emails and deletes nothing. Warnings, the Keep button and deletion come in later versions; see [docs/design.md](docs/design.md) for the plan.

Built entirely from wiki pages (Velocity, plus a Groovy engine). No Java to compile.

## Requirements

- Written for XWiki 17.10 LTS and 18.x. Older versions may work but haven't been checked. Smoke-tested on XWiki 17.10.14 (standalone Jetty and HSQLDB), installed by the superadmin account. Not yet tested on a production-style setup or with a normal admin account.
- An admin account **with programming rights** to install it. The engine is Groovy, and XWiki only runs Groovy from pages saved by such a user.

## Install

1. Get the XAR: build it (below) or download it from a release.
2. Log in as an admin with programming rights.
3. Go to **Administration > Content > Import**, upload the XAR, select all pages and import.
4. Go to **Administration > Content > Content expiry**. If you see an error saying the engine can't start, open `ContentExpiry.Code.Engine`, click Edit and save it without changes. XWiki may record the importing user, or the author in the file, as the page author depending on version and import options; the engine needs its page saved by someone with programming rights.

The import also adds space rights so only wiki admins can edit pages in the `ContentExpiry` space.

### Upgrading

Import the new XAR over the old one. Your settings are on `ContentExpiry.Code.Settings` and the engine's bookkeeping is on `ContentExpiry.Code.State`. Neither ships in the XAR, so an import can't overwrite them.

## Use

- **Administration > Content > Content expiry**: turn it on and set the retention period, warnings, which spaces it covers and which pages are exempt.
- **Administration > Content > Content expiry report**: counts, and a table of pages with when they were last changed, when they would expire and who would be warned.

The clock is the page's last-modified date, so any save restarts it. Nothing can expire until the longest warning period has passed after warnings are switched on, so every owner gets every warning even on the first run.

Always excluded, whatever the settings: hidden pages; the `XWiki`, `Scheduler`, `Panels` and `ContentExpiry` spaces; `Main.WebHome`; user and group profiles; pages holding application code (UI extensions, macros, admin sections, scheduler jobs, skin extensions, components, template providers, skins, blog categories); class definitions; and pages that belong to an installed extension. Pages with child pages are listed but will never be deleted automatically.

## Develop

```
python3 build.py                  # builds dist/content-expiry-<version>.xar
python3 build.py --check          # validates without writing dist/
python3 tests/run_policy_tests.py # unit tests for the date logic (needs a JDK)
```

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
| `ContentExpiry.Code.Engine` | Groovy engine: settings, queries, report, policy date logic |
| `ContentExpiry.Code.Admin` | Registers the two admin sections; holds the stylesheet |
| `ContentExpiry.Code.AdminSettingsSheet` / `AdminReportSheet` | Velocity for the two sections |
| `ContentExpiry.Code.Macros` | Shared Velocity macros |
| `ContentExpiry.Code.ConfigClass` / `StateClass` | Classes for settings and engine bookkeeping |
| `ContentExpiry.Code.Translations` | Admin section names |
| `ContentExpiry.WebPreferences` | Space rights: only admins can edit |

### Rules for the engine

- Every public method in the engine checks for wiki admin rights. `parseGroovyFromPage` only checks who saved the engine page, not who is calling it.
- The `Policy` class inside the engine must stay valid Java as well as Groovy, so the tests can compile it with `javac`. The test runner rejects `$`, single-quoted literals and `/` division in it.

## Licence

MIT. See [LICENSE](LICENSE).
