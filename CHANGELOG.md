# Changelog

## 0.4.0 (unreleased)

My pages, an email template that survives upgrades, and a release workflow.

- **My pages.** ContentExpiry.WebHome lists the pages the signed-in user would be warned about (owner, then creator, then last editor), with the reason and a Keep button for each. Emails and the Expiry tab link to it. Pages that fall back to the fallback contact are in the admin report instead.
- **Customizable warning email.** The shipped template is replaced by every import, so edits to it were lost on upgrade. "Make my own copy to edit" in the settings copies it to `ContentExpiry.Code.MailTemplateCustom`, which no import contains, and the engine uses that copy while it exists. "Go back to the shipped email" deletes the copy (to the recycle bin). Works for an admin without programming rights.
- **Send me a sample email** in the settings builds the email with made-up pages and sends it to the admin's own address, using whichever template is in use.
- New variable `$myPagesUrl` in the email template. The template page documents all the variables.
- Release workflow: start it from the Actions page on GitHub with a version (it creates the tag itself, and only runs from `main`), or push a tag such as `v0.4.0`. Either way it runs the unit tests, builds the XAR and publishes a GitHub release with this changelog section as the notes. Not yet used.
- `docs/acceptance-checklist.md`: what to check on a real instance, in order.
- `NOTICE` credits the XWiki class definitions that are embedded in the XAR. They are XWiki's own, extracted from 17.10.
- Fixed in tests: the lock XWiki puts on the global admin page by an earlier admin session is now released.

## 0.3.0 (unreleased)

Warn and delete. Pages nobody keeps go to the recycle bin.

- Warn and delete mode, with a confirmation tick in the settings and a refusal when the wiki's recycle bin is off.
- A page is deleted only after its final warning email was delivered for its current expiry date and the shortest warning period has passed since. The engine keeps a ledger of delivered warnings on `ContentExpiry.Code.State`. Pages that turn up late get a catch-up warning first. An edited page has a new expiry date and never matches. A restored page is warned again.
- At most 50 pages deleted per run (setting), each page re-checked just before deletion, pages with child pages and pages nobody can be emailed about never deleted.
- Audit log page per month, `ContentExpiry.Log.yyyy-MM`, visible to administrators only.
- The report lists pages waiting for an admin and links to the audit log. Emails and the banner say when a page will be deleted.
- Unit tests for the deletion rules (73 in all) and a live test, `tests/live/delete.js`, that steps time through the whole sequence.

## 0.2.0 (unreleased)

Warn only. Page owners are emailed before the expiry date and can keep a page with one click. Nothing is deleted.

- Warn only mode in the settings, with a sender address (default: the wiki admin email).
- Daily scheduler job, "Content Expiry daily run" at 03:17. It runs as the admin who last saved the settings. A threshold is warned once, a missed day is caught up, and failed emails are retried for 14 days.
- One digest email per person per run, from an editable `XWiki.Mail` template page.
- Owner, then creator, then last editor, then the fallback contact; groups are expanded to their members.
- Banner under the title of pages inside their warning window, with a Keep button for anyone who can edit the page. Anonymous visitors see the banner without the button.
- Expiry tab under each page: when it expires, who is warned (editors only) and an owner field.
- The report shows when the job last ran and what it did.
- Live test suite (`tests/live`) with a mail capture server, and unit tests for the warning thresholds.
- The daily job is reloaded in the scheduler on every settings save. The scheduler captures the run-as user when it schedules a job, so changing the job page alone did nothing, and the placeholder user from the import stayed in force. The first live install found this: the job failed with "needs wiki administration rights" while the page named the right admin. A failed run now says who it ran as, the report shows the reason and warns when the job's user isn't an admin.
- The settings form posts with `forceEdit=1` so a second admin isn't refused because another admin has the section open. The form saves to its own page, so the lock protected nothing.

## 0.1.0 (unreleased)

First version: report only. Nothing is emailed or deleted.

- Administration > Content > Content expiry: settings (on/off, retention period, warning days, included and excluded spaces, exempt pages, fallback contact). Settings are checked before saving and live on a page that is never shipped, so upgrades can't overwrite them.
- Administration > Content > Content expiry report: summary counts and a paged table of pages expiring within the longest warning, past the retention period, or all covered pages, with who would be warned.
- Safety floor: nothing can expire until the full warning period has passed after warnings are switched on, or after the retention period is shortened.
- Always excluded: hidden pages, system spaces, the wiki home page, user and group profiles, application code pages, class definitions and pages that belong to installed extensions.
- Fixed after the first run on XWiki 17.10.14: the engine failed to compile (private and public `isAdmin` overloads), the report page failed on a list literal method call, a warning text printed `$ceMaxWarning-day` literally, and saved settings were stamped with the Guest user as author and then ignored as untrusted.
- Build script producing an importable XAR, and unit tests for the date logic.
