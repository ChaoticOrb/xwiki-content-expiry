/*
 * Content Expiry for XWiki: engine.
 * https://github.com/ChaoticOrb/xwiki-content-expiry
 *
 * Loaded with $xwiki.parseGroovyFromPage('ContentExpiry.Code.Engine') from
 * Velocity (and later from the scheduler job). parseGroovyFromPage only checks
 * that THIS page was saved by a user with programming rights, not who is
 * calling it, so every public method checks for wiki admin rights itself.
 *
 * The Policy class at the bottom holds all the date arithmetic. It is written
 * in the subset of Groovy that is also valid Java, so tests/run_policy_tests.py
 * can compile and test it with plain javac. Between the POLICY markers: no $
 * in strings, no single-quoted strings, no integer division with / (Groovy
 * returns a decimal; use Math.floorDiv), no Groovy-only syntax.
 */

import com.xpn.xwiki.XWikiContext
import com.xpn.xwiki.doc.XWikiDocument
import com.xpn.xwiki.objects.BaseObject
import com.xpn.xwiki.web.Utils
import org.xwiki.context.Execution
import org.xwiki.model.reference.DocumentReference
import org.xwiki.model.reference.DocumentReferenceResolver
import org.xwiki.model.reference.EntityReferenceSerializer
import org.xwiki.model.reference.WikiReference
import org.xwiki.query.Query
import org.xwiki.query.QueryManager
import org.xwiki.script.service.ScriptServiceManager
import org.xwiki.security.authorization.AuthorizationManager
import org.xwiki.security.authorization.Right

class ContentExpiryEngine
{
    static final String VERSION = '0.1.0'

    static final List<String> CODE_SPACE = ['ContentExpiry', 'Code']

    /** Spaces that are never covered, whatever the admin configures. */
    static final List<String> TECHNICAL_SPACES = ['XWiki', 'Scheduler', 'Panels', 'ContentExpiry']

    /** Pages that are never covered. */
    static final List<String> ALWAYS_EXEMPT = ['Main.WebHome']

    /**
     * Pages holding any of these objects are code or structure, not content, and are never
     * covered: users, groups, UI extensions, macros, admin sections, scheduler jobs, skin
     * extensions, components, template providers, skins and blog categories.
     */
    static final List<String> TECHNICAL_CLASSES = [
        'XWiki.XWikiUsers', 'XWiki.XWikiGroups', 'XWiki.UIExtensionClass', 'XWiki.WikiMacroClass',
        'XWiki.ConfigurableClass', 'XWiki.SchedulerJobClass', 'XWiki.JavaScriptExtension',
        'XWiki.StyleSheetExtension', 'XWiki.ComponentClass', 'XWiki.TemplateProviderClass',
        'XWiki.XWikiSkins', 'Blog.CategoryClass'
    ]

    /** Modes in order of how much they do. Only 'report' is implemented in 0.1. */
    static final List<String> MODES = ['report', 'warn', 'delete']

    static final List<String> AVAILABLE_MODES = ['report']

    static final List<String> FILTERS = ['due', 'stale', 'all']

    String ping()
    {
        return 'ok'
    }

    String getVersion()
    {
        return VERSION
    }

    // ------------------------------------------------------------------------------------------
    // Context, references, rights
    // ------------------------------------------------------------------------------------------

    private XWikiContext xc()
    {
        return (XWikiContext) Utils.getComponent(Execution).getContext().getProperty('xwikicontext')
    }

    private DocumentReference codeRef(String page)
    {
        return new DocumentReference(xc().getWikiId(), CODE_SPACE, page)
    }

    private DocumentReference resolve(String reference)
    {
        DocumentReferenceResolver<String> resolver = Utils.getComponent(DocumentReferenceResolver.TYPE_STRING, 'current')
        return resolver.resolve(reference)
    }

    private String serializeLocal(DocumentReference ref)
    {
        EntityReferenceSerializer<String> serializer = Utils.getComponent(EntityReferenceSerializer.TYPE_STRING, 'local')
        return serializer.serialize(ref)
    }

    private boolean isAdminUser(DocumentReference userRef)
    {
        if (userRef == null) {
            return false
        }
        AuthorizationManager auth = Utils.getComponent(AuthorizationManager)
        return auth.hasAccess(Right.ADMIN, userRef, new WikiReference(xc().getWikiId()))
    }

    /** Records the acting admin as author. XWiki does not do this for documents saved from a script. */
    private void stamp(XWikiDocument doc)
    {
        DocumentReference user = xc().getUserReference()
        doc.setAuthorReference(user)
        doc.setContentAuthorReference(user)
        if (doc.isNew()) {
            doc.setCreatorReference(user)
        }
    }

    boolean isAdmin()
    {
        return isAdminUser(xc().getUserReference())
    }

    private void requireAdmin()
    {
        if (!isAdmin()) {
            throw new SecurityException('Content Expiry: this needs wiki administration rights.')
        }
    }

    // ------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------

    /**
     * Current settings with defaults applied. Settings live on ContentExpiry.Code.Settings,
     * which is created on first save and never shipped in the XAR, so upgrading by import
     * can't overwrite them.
     */
    Map getSettings()
    {
        requireAdmin()
        return readSettings()
    }

    private Map readSettings()
    {
        XWikiContext xc = xc()
        XWikiDocument doc = xc.getWiki().getDocument(codeRef('Settings'), xc)
        BaseObject obj = doc.getXObject(codeRef('ConfigClass'))

        Map s = [:]
        s.saved = obj != null
        // Settings saved by a non-admin (e.g. if someone loosened the space rights) are ignored.
        s.trusted = obj == null || isAdminUser(doc.getAuthorReference())
        if (!s.trusted) {
            obj = null
        }
        s.enabled = obj != null && obj.getIntValue('enabled', 0) == 1
        String mode = obj != null ? (obj.getStringValue('mode') ?: 'report') : 'report'
        s.mode = AVAILABLE_MODES.contains(mode) ? mode : 'report'
        int months = obj != null ? obj.getIntValue('retentionMonths', 60) : 60
        s.retentionMonths = months > 0 ? months : 60
        s.warningDays = Policy.parseWarningDays(obj != null ? obj.getStringValue('warningDays') : '')
        s.includedSpaces = lines(obj != null ? obj.getLargeStringValue('includedSpaces') : '')
        s.excludedSpaces = lines(obj != null ? obj.getLargeStringValue('excludedSpaces') : '')
        s.exemptPages = lines(obj != null ? obj.getLargeStringValue('exemptPages') : '')
        s.fallbackContact = (obj != null ? obj.getStringValue('fallbackContact') : '') ?: 'XWiki.XWikiAdminGroup'
        // Warnings and deletions only happen when enabled and not in report-only mode.
        s.active = s.enabled && s.mode != 'report'
        s.lastSavedBy = obj != null ? serializeLocal(doc.getAuthorReference()) : ''
        s.lastSavedOn = obj != null ? doc.getDate() : null
        // Ready-made text for the settings form.
        s.warningDaysText = s.warningDays.join(', ')
        s.includedSpacesText = s.includedSpaces.join('\n')
        s.excludedSpacesText = s.excludedSpaces.join('\n')
        s.exemptPagesText = s.exemptPages.join('\n')
        return s
    }

    private static List<String> lines(String text)
    {
        if (!text) {
            return []
        }
        return text.split(/[\r\n,]+/)*.trim().findAll { it }.unique()
    }

    /**
     * Validate and save settings from the admin form.
     *
     * @param input raw form values: enabled ('1' or absent), mode, retentionMonths, warningDays,
     *              includedSpaces, excludedSpaces, exemptPages, fallbackContact
     * @return [ok: true] or [ok: false, errors: [field: message]]
     */
    Map saveSettings(Map input)
    {
        requireAdmin()
        XWikiContext xc = xc()
        Map errors = [:]

        String mode = (input.mode ?: 'report').toString()
        if (!AVAILABLE_MODES.contains(mode)) {
            errors.mode = 'That mode is not available in this version.'
        }

        Integer months = null
        String monthsText = (input.retentionMonths ?: '').toString().trim()
        if (!(monthsText ==~ /\d{1,4}/)) {
            errors.retentionMonths = 'Enter a whole number of months, for example 60 for 5 years.'
        } else {
            months = monthsText.toInteger()
            if (months < 1 || months > 1200) {
                errors.retentionMonths = 'Enter a number of months between 1 and 1200.'
            }
        }

        String warningText = (input.warningDays ?: '').toString().trim()
        List<String> badWarnings = warningText.split(/[,;\s]+/).findAll { it && !(it ==~ /\d{1,4}/) }
        if (!warningText) {
            errors.warningDays = 'Enter at least one warning, for example 60, 30, 7.'
        } else if (badWarnings) {
            errors.warningDays = "Not a whole number of days: ${badWarnings.join(', ')}".toString()
        } else if (months != null && Policy.maxWarning(Policy.parseWarningDays(warningText)) > months * 28) {
            errors.warningDays = 'The longest warning is longer than the retention period.'
        }

        List<String> included = lines((input.includedSpaces ?: '').toString())
        List<String> excluded = lines((input.excludedSpaces ?: '').toString())
        List<String> exempt = lines((input.exemptPages ?: '').toString())
        List<String> missingSpaces = (included + excluded).findAll { !spaceExists(it) }
        if (missingSpaces) {
            errors.spaces = "These spaces don't exist (check spelling and capitals): ${missingSpaces.join(', ')}".toString()
        }
        List<String> missingPages = exempt.findAll { !xc.getWiki().exists(resolve(it), xc) }
        if (missingPages) {
            errors.exemptPages = "These pages don't exist: ${missingPages.join(', ')}".toString()
        }

        String fallback = (input.fallbackContact ?: '').toString().trim()
        if (fallback && !fallback.contains('@') && !xc.getWiki().exists(resolve(fallback), xc)) {
            errors.fallbackContact = 'Enter an existing user or group (e.g. XWiki.XWikiAdminGroup) or an email address.'
        }

        if (errors) {
            return [ok: false, errors: errors]
        }

        XWikiDocument doc = xc.getWiki().getDocument(codeRef('Settings'), xc).clone()
        BaseObject obj = doc.getXObject(codeRef('ConfigClass'), true, xc)
        obj.setIntValue('enabled', input.enabled ? 1 : 0)
        obj.setStringValue('mode', mode)
        obj.setIntValue('retentionMonths', months)
        obj.setStringValue('warningDays', Policy.parseWarningDays(warningText).join(', '))
        obj.setLargeStringValue('includedSpaces', included.join('\n'))
        obj.setLargeStringValue('excludedSpaces', excluded.join('\n'))
        obj.setLargeStringValue('exemptPages', exempt.join('\n'))
        obj.setStringValue('fallbackContact', fallback ?: 'XWiki.XWikiAdminGroup')
        doc.setHidden(true)
        if (doc.isNew()) {
            doc.setTitle('Content Expiry settings')
        }
        stamp(doc)
        xc.getWiki().saveDocument(doc, 'Settings updated', false, xc)
        return [ok: true]
    }

    private boolean spaceExists(String space)
    {
        List result = hql('select count(doc.id) from XWikiDocument doc where doc.space = :space or doc.space like :spaceLike escape \'!\'',
            [space: space, spaceLike: likeEscape(space) + '.%']).execute()
        return result && ((Number) result[0]).longValue() > 0
    }

    // ------------------------------------------------------------------------------------------
    // Policy date (the start of the safety floor)
    // ------------------------------------------------------------------------------------------

    /**
     * Nothing can expire sooner than policy date + longest warning. The policy date is reset
     * whenever warnings switch on, the retention period gets shorter, or the longest warning
     * gets longer, so every page always gets the full warning sequence. While warnings are off
     * there is no policy date and the floor is measured from today.
     *
     * Kept on ContentExpiry.Code.State, written only by the engine.
     *
     * @return the policy date in force, or null when warnings are off
     */
    private Date ensurePolicyDate(Map s)
    {
        XWikiContext xc = xc()
        XWikiDocument stateDoc = xc.getWiki().getDocument(codeRef('State'), xc)
        BaseObject state = stateDoc.getXObject(codeRef('StateClass'))

        boolean wasActive = state != null && state.getIntValue('active', 0) == 1
        int lastMonths = state != null ? state.getIntValue('retentionMonths', 0) : 0
        int lastMaxWarning = state != null ? state.getIntValue('maxWarningDays', 0) : 0
        Date policyDate = state != null ? state.getDateValue('policyDate') : null
        int maxWarning = Policy.maxWarning(s.warningDays)

        boolean reset = s.active && (
            !wasActive || policyDate == null || s.retentionMonths < lastMonths || maxWarning > lastMaxWarning)
        boolean changed = reset || wasActive != s.active ||
            (s.active && (lastMonths != s.retentionMonths || lastMaxWarning != maxWarning))

        if (changed) {
            XWikiDocument doc = stateDoc.clone()
            BaseObject obj = doc.getXObject(codeRef('StateClass'), true, xc)
            obj.setIntValue('active', s.active ? 1 : 0)
            obj.setIntValue('retentionMonths', s.retentionMonths)
            obj.setIntValue('maxWarningDays', maxWarning)
            if (reset) {
                policyDate = new Date()
                obj.setDateValue('policyDate', policyDate)
            }
            doc.setHidden(true)
            if (doc.isNew()) {
                doc.setTitle('Content Expiry state')
            }
            stamp(doc)
            xc.getWiki().saveDocument(doc, reset ? 'Policy date reset' : 'Policy state updated', true, xc)
        }
        return s.active ? policyDate : null
    }

    private Policy policy(Map s, Date policyDate)
    {
        return new Policy(s.retentionMonths, s.warningDays, policyDate)
    }

    // ------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------

    private static String likeEscape(String value)
    {
        return value.replace('!', '!!').replace('%', '!%').replace('_', '!_')
    }

    /** WHERE clause shared by every report query, plus its bound values. */
    private Map coverage(Map s)
    {
        List<String> where = []
        Map params = [:]

        where << 'doc.translation = 0'
        where << '(doc.hidden = false or doc.hidden is null)'

        where << 'doc.fullName not in (:exempt)'
        params.exempt = (ALWAYS_EXEMPT + s.exemptPages.collect { serializeLocal(resolve(it)) }).unique()

        where << ('not exists (select techObj.id from BaseObject techObj where techObj.name = doc.fullName ' +
            'and techObj.className in (:technicalClasses))')
        params.technicalClasses = TECHNICAL_CLASSES

        // A page that other objects point to as their class is a class definition.
        where << 'not exists (select usedObj.id from BaseObject usedObj where usedObj.className = doc.fullName)'

        int i = 0
        (TECHNICAL_SPACES + s.excludedSpaces).unique().each { space ->
            where << "(doc.space <> :exSpace${i} and doc.space not like :exLike${i} escape '!')".toString()
            params["exSpace${i}".toString()] = space
            params["exLike${i}".toString()] = likeEscape(space) + '.%'
            i++
        }

        if (s.includedSpaces) {
            List<String> ors = []
            int j = 0
            s.includedSpaces.each { space ->
                ors << "doc.space = :inSpace${j} or doc.space like :inLike${j} escape '!'".toString()
                params["inSpace${j}".toString()] = space
                params["inLike${j}".toString()] = likeEscape(space) + '.%'
                j++
            }
            where << '(' + ors.join(' or ') + ')'
        }
        return [where: where.join(' and '), params: params]
    }

    private Query hql(String statement, Map params)
    {
        QueryManager qm = Utils.getComponent(QueryManager)
        Query query = qm.createQuery(statement, Query.HQL)
        query.setWiki(xc().getWikiId())
        params.each { k, v -> query.bindValue(k.toString(), v) }
        return query
    }

    private long count(String where, Map params)
    {
        List result = hql("select count(doc.id) from XWikiDocument doc where ${where}".toString(), params).execute()
        return result ? ((Number) result[0]).longValue() : 0L
    }

    /**
     * Extra WHERE clause and values for a filter, or null if the filter can't match anything
     * (for example 'due' while the safety floor is further away than the longest warning).
     */
    private Map filterClause(String filter, Policy p, Map s, Date now)
    {
        if (filter == 'all') {
            return [where: '', params: [:]]
        }
        Date cutoff
        if (filter == 'stale') {
            cutoff = p.retentionCutoff(now)
        } else {
            cutoff = p.lastModifiedCutoff(Policy.addDays(now, Policy.maxWarning(s.warningDays)), now)
        }
        if (cutoff == null) {
            return null
        }
        return [where: ' and doc.date <= :cutoff', params: [cutoff: cutoff]]
    }

    // ------------------------------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------------------------------

    /**
     * Figures for the summary: covered pages, pages past their retention age, and pages
     * reaching each warning threshold. Counts come from the database query alone; a few
     * pages it counts may turn out to be excluded by the per-page checks in the table
     * (extension pages, class definitions).
     */
    Map summary()
    {
        requireAdmin()
        Map s = readSettings()
        Date policyDate = ensurePolicyDate(s)
        Policy p = policy(s, policyDate)
        Map cov = coverage(s)
        Date now = new Date()

        Map out = [settings: s, policyDate: policyDate, floorDate: p.floorDate(now), now: now]
        out.covered = count(cov.where, cov.params)
        Map stale = filterClause('stale', p, s, now)
        out.stale = count(cov.where + stale.where, cov.params + stale.params)
        out.buckets = s.warningDays.collect { int days ->
            Date cutoff = p.lastModifiedCutoff(Policy.addDays(now, days), now)
            long n = cutoff == null ? 0L : count(cov.where + ' and doc.date <= :cutoff', cov.params + [cutoff: cutoff])
            [days: days, count: n]
        }
        return out
    }

    /**
     * One page of the report table.
     *
     * @param filter 'due' (expiring within the longest warning), 'stale' (past the retention
     *               age, ignoring the safety floor) or 'all' (every covered page)
     * @param offset first row, zero based
     * @param limit  rows per page, 1 to 200
     */
    Map report(String filter, int offset, int limit)
    {
        requireAdmin()
        Map s = readSettings()
        Date policyDate = ensurePolicyDate(s)
        Policy p = policy(s, policyDate)
        Map cov = coverage(s)
        Date now = new Date()

        String f = FILTERS.contains(filter) ? filter : 'due'
        int lim = Math.max(1, Math.min(limit, 200))
        int off = Math.max(0, offset)
        Map out = [filter: f, offset: off, limit: lim, total: 0L, rows: [], settings: s,
            policyDate: policyDate, floorDate: p.floorDate(now), now: now]

        Map clause = filterClause(f, p, s, now)
        if (clause == null) {
            return out
        }
        String where = cov.where + clause.where
        Map params = cov.params + clause.params
        out.total = count(where, params)

        Query q = hql(("select doc.fullName, doc.date from XWikiDocument doc where ${where} " +
            'order by doc.date asc, doc.fullName asc').toString(), params)
        q.setOffset(off)
        q.setLimit(lim)
        out.rows = q.execute().collect { Object[] r -> row((String) r[0], (Date) r[1], p, s, now) }
        return out
    }

    private Map row(String fullName, Date lastModified, Policy p, Map s, Date now)
    {
        XWikiContext xc = xc()
        DocumentReference ref = resolve(fullName)
        XWikiDocument doc = xc.getWiki().getDocument(ref, xc)
        Date expiry = p.expiry(lastModified, now)

        Map r = [:]
        r.fullName = fullName
        r.title = doc.getRenderedTitle(xc) ?: ref.getName()
        r.url = doc.getURL('view', xc)
        r.lastModified = lastModified
        r.lastEditor = doc.getAuthorReference() != null ? serializeLocal(doc.getAuthorReference()) : ''
        r.retentionReached = Policy.addMonths(lastModified, s.retentionMonths)
        r.expiry = expiry
        r.daysLeft = p.daysLeft(expiry, now)
        r.status = p.status(expiry, now)
        r.note = ''
        r.hasChildren = false

        // Checks too costly or awkward for the query. These pages are reported, never deleted.
        if (isExtensionPage(ref)) {
            r.status = 'excluded'
            r.note = 'Belongs to an installed extension'
        } else if (!doc.getXClass().getPropertyList().isEmpty()) {
            r.status = 'excluded'
            r.note = 'Defines a class'
        } else if (hasChildren(doc)) {
            r.hasChildren = true
            r.note = 'Has child pages, so it will be listed for an admin to decide instead of being deleted'
        }

        Map contact = contactFor(doc, s)
        r.contact = contact.name
        r.contactSource = contact.source
        r.contactProblem = contact.problem
        return r
    }

    private boolean isExtensionPage(DocumentReference ref)
    {
        try {
            def service = Utils.getComponent(ScriptServiceManager).get('extension.xar')
            return service != null && service.isExtensionDocument(ref) == Boolean.TRUE
        } catch (Exception e) {
            return false
        }
    }

    private boolean hasChildren(XWikiDocument doc)
    {
        if (doc.getDocumentReference().getName() != 'WebHome') {
            return false
        }
        String space = doc.getSpace()
        return count("doc.translation = 0 and doc.fullName <> :self and (doc.space = :space or doc.space like :spaceLike escape '!')",
            [self: doc.getFullName(), space: space, spaceLike: likeEscape(space) + '.%']) > 0
    }

    // ------------------------------------------------------------------------------------------
    // Who gets warned
    // ------------------------------------------------------------------------------------------

    /**
     * Owner field (if set), then creator, then last editor, skipping anyone who no longer
     * exists, is disabled or has no email address. Falls back to the configured contact.
     */
    private Map contactFor(XWikiDocument doc, Map s)
    {
        List<Map> candidates = []
        BaseObject owner = doc.getXObject(codeRef('OwnerClass'))
        if (owner != null && owner.getStringValue('owner')) {
            candidates << [ref: resolve(owner.getStringValue('owner')), source: 'owner']
        }
        if (doc.getCreatorReference() != null) {
            candidates << [ref: doc.getCreatorReference(), source: 'creator']
        }
        if (doc.getAuthorReference() != null) {
            candidates << [ref: doc.getAuthorReference(), source: 'last editor']
        }

        List<String> problems = []
        Set<DocumentReference> tried = new HashSet<>()
        for (Map c : candidates) {
            DocumentReference userRef = (DocumentReference) c.ref
            if (!tried.add(userRef)) {
                continue
            }
            String problem = userProblem(userRef)
            if (problem == null) {
                return [name: serializeLocal(userRef), source: c.source, problem: problems.join('; ')]
            }
            problems << "${c.source} ${serializeLocal(userRef)}: ${problem}".toString()
        }
        return [name: s.fallbackContact, source: 'fallback', problem: problems.join('; ')]
    }

    /** Null if the user can be emailed, otherwise the reason they can't. */
    private String userProblem(DocumentReference userRef)
    {
        XWikiContext xc = xc()
        if (userRef.getName() == 'XWikiGuest' || userRef.getName() == 'superadmin') {
            return 'not a real user'
        }
        if (!xc.getWiki().exists(userRef, xc)) {
            return 'no longer exists'
        }
        XWikiDocument userDoc = xc.getWiki().getDocument(userRef, xc)
        BaseObject user = userDoc.getXObject(new DocumentReference(userRef.getWikiReference().getName(), 'XWiki', 'XWikiUsers'))
        if (user == null) {
            return 'not a user profile'
        }
        if (user.getIntValue('active', 1) != 1) {
            return 'account disabled'
        }
        if (!user.getStringValue('email')) {
            return 'no email address'
        }
        return null
    }

    // POLICY-BEGIN
    static class Policy
    {
        private final int retentionMonths;

        private final java.util.List<Integer> warningDays;

        private final java.util.Date policyDate;

        /**
         * @param retentionMonths months after the last change before a page expires
         * @param warningDays warning thresholds in days before expiry, largest first
         * @param policyDate start of the safety floor, or null to measure the floor from today
         */
        Policy(int retentionMonths, java.util.List<Integer> warningDays, java.util.Date policyDate)
        {
            this.retentionMonths = retentionMonths;
            this.warningDays = warningDays;
            this.policyDate = policyDate;
        }

        /** Whole numbers from 1 to 3650, largest first, duplicates removed. Default 60, 30, 7. */
        static java.util.List<Integer> parseWarningDays(String text)
        {
            java.util.TreeSet<Integer> days = new java.util.TreeSet<Integer>(java.util.Collections.reverseOrder());
            if (text != null) {
                for (String part : text.split("[,;\\s]+")) {
                    String trimmed = part.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    try {
                        int value = Integer.parseInt(trimmed);
                        if (value > 0 && value <= 3650) {
                            days.add(Integer.valueOf(value));
                        }
                    } catch (NumberFormatException e) {
                        // Ignore anything that isn't a whole number.
                    }
                }
            }
            if (days.isEmpty()) {
                days.add(Integer.valueOf(60));
                days.add(Integer.valueOf(30));
                days.add(Integer.valueOf(7));
            }
            return new java.util.ArrayList<Integer>(days);
        }

        static int maxWarning(java.util.List<Integer> warningDays)
        {
            int max = 0;
            for (Integer d : warningDays) {
                max = Math.max(max, d.intValue());
            }
            return max;
        }

        static int minWarning(java.util.List<Integer> warningDays)
        {
            int min = Integer.MAX_VALUE;
            for (Integer d : warningDays) {
                min = Math.min(min, d.intValue());
            }
            return min == Integer.MAX_VALUE ? 0 : min;
        }

        static java.util.Date addDays(java.util.Date date, int days)
        {
            return java.util.Date.from(date.toInstant().atZone(java.time.ZoneId.systemDefault()).plusDays(days).toInstant());
        }

        static java.util.Date addMonths(java.util.Date date, int months)
        {
            return java.util.Date.from(date.toInstant().atZone(java.time.ZoneId.systemDefault()).plusMonths(months).toInstant());
        }

        /** Earliest date anything can expire: policy date (or now, if none) plus the longest warning. */
        java.util.Date floorDate(java.util.Date now)
        {
            java.util.Date base = this.policyDate != null ? this.policyDate : now;
            return addDays(base, maxWarning(this.warningDays));
        }

        /** When the page will expire: retention age, but never before the safety floor. */
        java.util.Date expiry(java.util.Date lastModified, java.util.Date now)
        {
            java.util.Date byAge = addMonths(lastModified, this.retentionMonths);
            java.util.Date floor = floorDate(now);
            return byAge.after(floor) ? byAge : floor;
        }

        /** Latest last-modified date that has reached the retention age by the given date. */
        java.util.Date retentionCutoff(java.util.Date by)
        {
            java.util.Date cutoff = addMonths(by, -this.retentionMonths);
            // plusMonths clamps to the end of shorter months, so step forward while the
            // next day still reaches retention by the date.
            while (!addMonths(addDays(cutoff, 1), this.retentionMonths).after(by)) {
                cutoff = addDays(cutoff, 1);
            }
            return cutoff;
        }

        /**
         * Latest last-modified date whose expiry falls on or before the given date, or null if
         * nothing can expire by then because of the safety floor.
         */
        java.util.Date lastModifiedCutoff(java.util.Date by, java.util.Date now)
        {
            if (floorDate(now).after(by)) {
                return null;
            }
            return retentionCutoff(by);
        }

        /** Whole days from now until expiry, rounded up; zero or negative once expired. */
        long daysLeft(java.util.Date expiry, java.util.Date now)
        {
            long millis = expiry.getTime() - now.getTime();
            long day = 24L * 60L * 60L * 1000L;
            if (millis <= 0) {
                return -Math.floorDiv(-millis, day);
            }
            return Math.floorDiv(millis + day - 1, day);
        }

        /** expired, due (within the shortest warning), warning (within the longest), or ok. */
        String status(java.util.Date expiry, java.util.Date now)
        {
            if (!expiry.after(now)) {
                return "expired";
            }
            long left = daysLeft(expiry, now);
            if (left <= minWarning(this.warningDays)) {
                return "due";
            }
            if (left <= maxWarning(this.warningDays)) {
                return "warning";
            }
            return "ok";
        }
    }
    // POLICY-END
}
