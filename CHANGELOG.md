# Changelog

## 0.1.0 (unreleased)

First version: report only. Nothing is emailed or deleted.

- Administration > Content > Content expiry: settings (on/off, retention period, warning days, included and excluded spaces, exempt pages, fallback contact). Settings are checked before saving and live on a page that is never shipped, so upgrades can't overwrite them.
- Administration > Content > Content expiry report: summary counts and a paged table of pages expiring within the longest warning, past the retention period, or all covered pages, with who would be warned.
- Safety floor: nothing can expire until the full warning period has passed after warnings are switched on, or after the retention period is shortened.
- Always excluded: hidden pages, system spaces, the wiki home page, user and group profiles, application code pages, class definitions and pages that belong to installed extensions.
- Fixed after the first run on XWiki 17.10.14: the engine failed to compile (private and public `isAdmin` overloads), the report page failed on a list literal method call, a warning text printed `$ceMaxWarning-day` literally, and saved settings were stamped with the Guest user as author and then ignored as untrusted.
- Build script producing an importable XAR, and unit tests for the date logic.
