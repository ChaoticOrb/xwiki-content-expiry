# Content Expiry: design spec

**Status:** v0.3 of the spec, 9 Nov 2026. Versions 0.1 (report only) and 0.2 (warn only) of the extension are built and tested on XWiki 17.10.14 with `tests/live`. Nothing beyond that. 0.3 adds deletion.

Pages that haven't been touched for a configurable period (default 5 years) are flagged, their owner is warned ahead of time, and if nobody keeps them they go to the XWiki recycle bin. Admins configure it from the normal Administration screen. Everyone else only sees warnings and a Keep button.

XWiki Standard has no expiry or retention feature, and no extension for it turned up in the extension repository or forum.

Anything marked **(unverified)** is an expectation, not something confirmed in XWiki source or on a live instance.

---

## 1. Decisions

| Question | Decision |
|---|---|
| What the clock measures | Last modified date (`doc.date`). Any save resets it, including metadata changes and Keep. |
| Who gets warned | Optional per-page owner field, then creator, then last editor, then an admin-configured fallback contact. Anyone who no longer exists, is disabled or has no email is skipped. |
| What happens at expiry | Normal delete to the recycle bin. Admins can restore. |
| How an owner keeps a page | One-click Keep button, which saves the page and so resets the clock. |
| Who configures it | Wiki admins only. |
| How it's built | Wiki pages only: Velocity UI plus a Groovy engine loaded with `parseGroovyFromPage`. No Java build. |

## 2. Behaviour

### The clock

Expiry = **max(last modified + retention period, safety floor)**.

XWiki stores `doc.date` (any save) and `doc.contentUpdateDate` (content only). Using `doc.date` means a Keep click, an owner change or a classification change counts as someone attending to the page. A bulk script that re-saves every page would reset every clock; admin docs should say so.

### The safety floor

Without it, switching warnings on would delete every page older than the retention period as soon as the first warning period ran out, with no chance for owners to be warned at every threshold. The same would happen when the period is shortened.

**Floor = policy date + longest warning.** The policy date (on `ContentExpiry.Code.State`) is set when warnings switch on, and reset when the retention period gets shorter or the longest warning gets longer. While warnings are off (report-only mode) there is no policy date and the report measures the floor from today, so it shows what would happen if warnings started now.

### Warnings (0.2, built)

Default **60, 30 and 7 days** before expiry. The daily job sends a warning when a threshold (expiry minus that many days) falls after the previous completed run and up to now, so a missed day doesn't skip a warning and a run never repeats one. The first window starts one second before the policy date, because floor-bound pages hit their first threshold exactly then. Each person gets **one digest email per run** listing all their pages that hit a threshold; people in a fallback group each get their own copy.

If an email fails, the run still completes so nobody else is emailed twice, and the failed person's pages are kept on `ContentExpiry.Code.State` and retried every run for 14 days. This matters for 0.3: deletion must never rely on a warning that was only attempted. Pages with child pages, excluded pages and pages with nobody to email are skipped and counted in the run result.

The job is a Groovy scheduler job whose script calls `runScheduled()`. The scheduler runs it as the job's `contextUser`, which the engine sets to the admin who last saved the settings. Checked on 17.10.14: an admin without programming rights works for that, because the job page keeps the author it was imported with. The engine still checks admin rights on every run.

### Keep (0.2, built)

- A banner under the page title (`org.xwiki.platform.template.content.header.after`) on pages within their warning window, with a **Keep this page** button for anyone with edit rights. Visitors without edit rights see the banner and a hint to sign in. Pages with child pages get no banner.
- Clicking posts to the page with a CSRF token. The engine saves the page as a minor edit with the comment "Kept by Content Expiry", resetting `doc.date` and leaving an audit trail in history. XWiki ignores a save that changes nothing, so the engine marks the metadata dirty to force it. It sets the author to the person who clicked and does not touch the content author, so scripts on the page don't start running with that person's rights.
- Emails link to the page, not to a "keep" URL: a link that changes data on GET is a CSRF risk and mail scanners pre-fetch links.

### Deletion (0.3)

On the expiry date the job deletes the page to the recycle bin with its translations and writes a line to the audit log.

**Pages with children are never deleted automatically.** In nested pages, deleting a parent orphans its children. They are listed for an admin instead.

### Which pages are covered

- **Configurable:** included spaces (default: all), excluded spaces, exempt pages. Exemptions live in the settings, not on pages, so editors can't exempt their own pages through the object editor.
- **Always excluded:** translations as separate rows (they follow their default page); hidden pages; the `XWiki`, `Scheduler`, `Panels` and `ContentExpiry` spaces; `Main.WebHome`; pages holding objects of `XWiki.XWikiUsers`, `XWiki.XWikiGroups`, `XWiki.UIExtensionClass`, `XWiki.WikiMacroClass`, `XWiki.ConfigurableClass`, `XWiki.SchedulerJobClass`, `XWiki.JavaScriptExtension`, `XWiki.StyleSheetExtension`, `XWiki.ComponentClass`, `XWiki.TemplateProviderClass`, `XWiki.XWikiSkins` or `Blog.CategoryClass`; pages used as a class by any object; pages with a class definition; pages that belong to an installed extension (`$services.extension.xar.isExtensionDocument`, since XWiki 11.10).
- The first group of exclusions is in the database query. Class definitions and extension pages are checked per row in the report table, so summary counts may include a few pages the table then marks as excluded.

## 3. Admin UI

Two sections under **Administration > Content**, registered with `XWiki.ConfigurableClass` on `ContentExpiry.Code.Admin`:

**Content expiry (settings).** A custom form (not ConfigurableClass's built-in one) so that the engine can validate input and so that settings can live on a page that isn't shipped. Posts back to the same admin URL with a CSRF token; the engine checks values and saves them to `ContentExpiry.Code.Settings`.

| Setting | Default |
|---|---|
| Enabled | Off |
| Mode | Report only. Warn only and Warn and delete are shown but disabled until built. |
| Retention period | 60 months |
| Warning thresholds | 60, 30, 7 days |
| Only these spaces / never these spaces | All / none |
| Exempt pages | none |
| Fallback contact | `XWiki.XWikiAdminGroup` (user, group or email) |

Settings saved by a non-admin are ignored (defaults used, with a warning), as a second line of defence behind the space rights.

**Content expiry report.** Status line, summary cards (covered pages, past retention, would expire within each warning threshold), filters (expiring within the longest warning, past the retention period, all), and a paged table: page, last changed and by whom, retention reached, would expire, status, who would be warned and why (owner, creator, last editor or fallback), and notes (children, exclusions, contacts that can't be emailed).

Go-live is meant to be: install, look at the report for a few weeks, adjust exclusions, then switch warnings on.

## 4. Owner UI

- The banner and Keep button (0.2, built).
- An **Expiry** tab under the page (0.2, built) with an Owner field for people who can edit the page, showing when the page expires and, to editors only, who would be warned. The first design put the field in the Information tab, but that tab is built into the skin and can't be extended, so this is its own tab, registered on `org.xwiki.plaftorm.template.docextra` (the misspelling is XWiki's). The owner form posts to the page and is handled by the banner extension, because a tab's content is only rendered when the tab loads.
- A "My pages due for review" page listing pages the current user would be warned about (0.4).

## 5. Architecture

Wiki pages only (XAR). The engine is one Groovy class on `ContentExpiry.Code.Engine`, loaded by Velocity with `$xwiki.parseGroovyFromPage` and later by the scheduler job. The scheduler requires Groovy job scripts and runs with its configured execution user (Scheduler Application docs; set the user explicitly, see XWIKI-19765).

- `parseGroovyFromPage` checks only that the engine page was saved by a user with programming rights, not who calls it. Every public engine method therefore checks for wiki admin rights.
- The date logic is a nested `Policy` class written in the Java-compatible subset of Groovy, unit-tested with `javac` by `tests/run_policy_tests.py`.
- Queries use HQL through the `QueryManager` component. XWiki binds collection parameters with `setParameterList` and doesn't apply its script-level HQL restrictions to queries created directly through the component (checked in `HqlQueryExecutor`).

## 6. Data model

| Page | Contains | Shipped |
|---|---|---|
| `ContentExpiry.Code.ConfigClass` | Settings class | Yes |
| `ContentExpiry.Code.Settings` | One ConfigClass object: the live settings | **No**, created on first save |
| `ContentExpiry.Code.StateClass` | `active`, `policyDate`, `retentionMonths`, `maxWarningDays`, `lastRun`, `lastResult`, `retry` | Yes |
| `ContentExpiry.Code.State` | Engine bookkeeping | **No**, created by the engine |
| `ContentExpiry.Code.Engine` | Groovy engine | Yes |
| `ContentExpiry.Code.Admin` | ConfigurableClass objects, stylesheet | Yes |
| `ContentExpiry.Code.AdminSettingsSheet`, `AdminReportSheet`, `Macros` | Velocity | Yes |
| `ContentExpiry.Code.Translations` | Admin section names | Yes |
| `ContentExpiry.WebPreferences` | Edit, delete and comment allowed to `XWiki.XWikiAdminGroup` only | Yes |
| `ContentExpiry.Code.OwnerClass` | `owner` (user), added to a page by the Expiry tab | Yes |
| `ContentExpiry.Code.Job` | Scheduler job | Yes |
| `ContentExpiry.Code.MailTemplate` | `XWiki.Mail` object for the digest | Yes |
| `ContentExpiry.Code.PageUI` | UI extensions (banner, Expiry tab) and their stylesheet | Yes |
| `ContentExpiry.Log.<yyyy-mm>` | Audit log | 0.3 |

Email uses the Mail Sender API (`mail.sender` script service, loaded from the engine) with a template page holding an `XWiki.Mail` object. Checked on 17.10.14: `createMessage('template', ref, [velocityVariables: ...])` renders the template, but the `headers` parameter did not set recipients, so the engine sets From and To with javax.mail. The template page's variables are `recipientName`, `items` (title, reference, url, lastModifiedText, expiryText, daysLeft, threshold, contactSource), `count`, `mode`, `retentionMonths`, `wikiName`, `wikiUrl`. The wiki's `title` preference can't be used as a name: it is itself a Velocity template. In-app notifications are a maybe: **(unverified)** whether a user-targeted notification type can be defined without Java.

## 7. Roadmap

| Version | Adds |
|---|---|
| 0.1 | Settings, report, safety floor (report only). Built, tested |
| 0.2 | Owner field, scheduler job, digest emails, banner and Keep button, Warn-only mode. Built, tested |
| 0.3 | Deletion to recycle bin, audit log, children list for admins, Warn-and-delete mode |
| 0.4 | "My pages" view, editable email template, acceptance tests on a live instance, first release |

## 8. Open questions

1. Which XWiki version will work run?
2. Spaces or page types that must never expire (policies, governance, legal)?
3. Blog posts: covered or excluded by default?
4. SMTP at work and the sender address.
5. Does the Confluence migrator keep original modified dates? Decides the size of the first wave.
6. Records-retention rules (public sector) that would require archiving instead of deletion.
7. Who XWiki records as author of imported pages. Checked on 17.10.14: the importing user (superadmin in the test). Still to check with a normal admin account that has programming rights. Also found: documents saved from the engine get no author unless the engine sets it, so it now stamps the acting admin itself.
8. Cost of the banner extension. It runs on every page view and exits early when warnings are off or the page isn't near expiry, but it has not been timed on a large wiki.
9. Email language and the user's own locale. Emails are English only.
10. Whether the real SMTP server at work needs authentication or TLS. The tests use a plain local capture server.
11. XWiki's ConfigurableClass framework locks the section's registration page while an admin has it open, and a second admin sees "locked by". That is standard for every configurable section, not something this extension adds.

## Sources

- [XWiki forum: The lifetime of the page](https://forum.xwiki.org/t/the-lifetime-of-the-page/3737)
- [Scheduler Application](https://extensions.xwiki.org/xwiki/bin/view/Extension/Scheduler+Application)
- [Mail Sender API](https://extensions.xwiki.org/xwiki/bin/view/Extension/Mail/MailSenderAPI)
- [XWIKI-19765](https://jira.xwiki.org/browse/XWIKI-19765), [XWIKI-21673](https://jira.xwiki.org/browse/XWIKI-21673)
- xwiki-platform source (master, Nov 2026): `XWiki/ConfigurableClass.xml`, `XWiki/ConfigurableClassMacros.xml`, `com/xpn/xwiki/api/XWiki.java` (`parseGroovyFromPage`), `AdminAction.java`, `HqlQueryExecutor.java`, `XarExtensionScriptService.java`, `xwiki.hbm.xml`
