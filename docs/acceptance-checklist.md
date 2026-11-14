# Acceptance checklist for a real instance

The live test suite in `tests/live` runs against a throwaway XWiki. This is the list to work through on the instance you actually care about, in order, before relying on it. Take a snapshot first. Each step says what you should see. It was written from installing 0.2 and 0.3 on a real homelab XWiki 17.10, and the numbered points are the things that went wrong there.

## 1. Install

- [ ] Import the XAR as an admin with programming rights (Administration, Content, Import). The result says 14 pages installed and 0 with errors.
- [ ] Administration, Content has two new entries, Content expiry and Content expiry report, and both open without an error.
- [ ] Open Content expiry, tick Turn on, leave the mode on Report only, Save. You see "Settings saved."
- [ ] **After every import, open Content expiry and click Save.** The scheduler fixes the job's user when it schedules it, and an import schedules it with a placeholder, so without this the daily job can fail with "needs wiki administration rights". The report warns in red when the job's user is not an admin.

## 2. Report only

- [ ] The report's counts look plausible for your wiki, and the table shows pages you expect with the right "Last changed" dates.
- [ ] Pages you never want touched (policies, legal) are covered by "Never these spaces" or "Exempt pages". Run this for a few weeks and read the report again.
- [ ] Open ContentExpiry.WebHome (the "My pages" page). It says Content expiry is not warning anyone yet.

## 3. Mail

- [ ] Administration, Mail, Mail Sending has your server details. Port 465 needs `mail.smtp.ssl.enable=true` in Additional Properties, port 587 needs `mail.smtp.starttls.enable=true`.
- [ ] In Content expiry, set a Sender address, then use **Send me a sample email**. It arrives at the address on your own profile, with made-up pages and no leftover `$` variables in the subject or body. If it does not arrive, Mail, Mail Sending Status shows the error.
- [ ] Every user who should be warned has an email address on their profile.

## 4. Warn only, on a small scope

- [ ] Put one test space in "Only these spaces", choose Warn only, Save.
- [ ] Make an old page in that space (see the snippet in the README's test section, or edit a page and rely on a short retention period).
- [ ] In Administration, Scheduler, Trigger "Content Expiry daily run". The report's "daily job last finished" line says it warned about the page, and an email arrives for the page's owner, creator or last editor.
- [ ] Open the page: the banner with **Keep this page** shows. Click it. The banner goes and the page history has a "Kept by Content Expiry" edit.
- [ ] Open the Expiry tab on a page, set an owner, and check the warnings now go to that person.
- [ ] Sign in as an ordinary user: ContentExpiry.WebHome lists their pages, each with a Keep button.
- [ ] Widen the scope only when this works.

## 5. Warn and delete, on a small scope

Only after warn only has run for at least a full warning cycle.

- [ ] The wiki's recycle bin is on (`xwiki.recyclebin` in `xwiki.cfg` is not 0).
- [ ] Select a test space, set "Most pages deleted per run" to a low number such as 2, choose Warn and delete, tick the confirmation, Save.
- [ ] Nothing is deleted until a page has had its final warning delivered and the shortest warning period (7 days by default) has passed since. The report says how many expired pages are still waiting.
- [ ] After a deletion: the page is in the recycle bin, the audit page ContentExpiry.Log.yyyy-MM lists it (and an ordinary user cannot open that page), and an admin can restore the page from the recycle bin. A restored page is warned again, not deleted at once. A deleted page's translations have their own recycle bin entries.
- [ ] Pages with child pages, and pages nobody can be emailed about, appear under "Waiting for an admin" and are never deleted.

## 6. Customizing the email

- [ ] **Make my own copy to edit**, then edit the Mail object (subject, text, HTML). **Send me a sample email** shows the change.
- [ ] Import the XAR again: the copy is still there and still used.

## Not covered by any test

XWiki 18.x, a mail server that needs authentication in your environment, the job firing at 03:17 on its own, and how the banner behaves on a large wiki. Check these if they matter to you.
