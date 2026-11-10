/*
 * Content Expiry for XWiki: engine.
 * https://github.com/ChaoticOrb/xwiki-content-expiry
 *
 * Loaded with $xwiki.parseGroovyFromPage('ContentExpiry.Code.Engine') from
 * Velocity (and later from the scheduler job). parseGroovyFromPage only checks
 * that THIS page was saved by a user with programming rights, not who is
 * calling it, so every public method checks rights itself: wiki admin rights for everything
 * except bannerFor, pageInfo, keep and setOwner, which are for ordinary users and check that
 * user's own view or edit right on the page they are given.
 *
 * The Policy class at the bottom holds all the date arithmetic. It is written
 * in the subset of Groovy that is also valid Java, so tests/run_policy_tests.py
 * can compile and test it with plain javac. Between the POLICY markers: no $
 * in strings, no single-quoted strings, no integer division with / (Groovy
 * returns a decimal; use Math.floorDiv), no Groovy-only syntax.
 */

import com.xpn.xwiki.XWikiContext
import javax.mail.Message
import javax.mail.internet.InternetAddress
import org.slf4j.LoggerFactory
import org.xwiki.user.group.GroupManager
import org.xwiki.wiki.descriptor.WikiDescriptorManager
import java.util.concurrent.atomic.AtomicBoolean
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
    static final String VERSION = '0.2.0'

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

    /** Modes in order of how much they do. 'delete' arrives in 0.3. */
    static final List<String> MODES = ['report', 'warn', 'delete']

    static final List<String> AVAILABLE_MODES = ['report', 'warn']

    /** Only one run at a time per server. A new engine class (after an import) starts unlocked. */
    /** How long a failed warning keeps being retried. */
    static final int RETRY_DAYS = 14

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false)

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
        if (user == null) {
            return
        }
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
        s.senderAddress = (obj != null ? obj.getStringValue('senderAddress') : '') ?: ''
        s.effectiveSender = s.senderAddress ?: adminEmail()
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

    private String adminEmail()
    {
        XWikiContext xc = xc()
        return (xc.getWiki().getXWikiPreference('admin_email', xc) ?: '').trim()
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
     *              includedSpaces, excludedSpaces, exemptPages, fallbackContact, senderAddress
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

        String sender = (input.senderAddress ?: '').toString().trim()
        if (sender && !(sender ==~ /[^@\s]+@[^@\s]+\.[^@\s]+/)) {
            errors.senderAddress = 'Enter an email address such as wiki@example.com, or leave it empty to use the wiki admin email.'
        } else if (mode == 'warn' && !sender && !adminEmail()) {
            errors.senderAddress = 'Warnings need a sender address, and the wiki has no admin email set. Enter one here.'
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
        obj.setStringValue('senderAddress', sender)
        doc.setHidden(true)
        if (doc.isNew()) {
            doc.setTitle('Content Expiry settings')
        }
        stamp(doc)
        xc.getWiki().saveDocument(doc, 'Settings updated', false, xc)
        syncJob()
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
        return ensurePolicyDate(s, new Date())
    }

    /** A reset stamps the policy date with the given time, so a run's own clock and the date agree. */
    private Date ensurePolicyDate(Map s, Date at)
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
                policyDate = at
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
        Map state = readState()
        out.lastRun = state.lastRun
        out.lastResult = state.lastResult
        out.jobUser = getJobUser()
        out.jobUserAdmin = isJobUserAdmin()
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
        r.externalUrl = doc.getExternalURL('view', xc)
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

    // ------------------------------------------------------------------------------------------
    // Run state
    // ------------------------------------------------------------------------------------------

    /** What the engine remembers between runs, from ContentExpiry.Code.State. */
    private Map readState()
    {
        XWikiContext xc = xc()
        BaseObject state = xc.getWiki().getDocument(codeRef('State'), xc).getXObject(codeRef('StateClass'))
        return [
            policyDate: state != null ? state.getDateValue('policyDate') : null,
            lastRun: state != null ? state.getDateValue('lastRun') : null,
            lastResult: state != null ? (state.getLargeStringValue('lastResult') ?: '') : '',
            retry: state != null ? (state.getLargeStringValue('retry') ?: '') : ''
        ]
    }

    /** Saves the state page after letting the closure change its StateClass object. */
    private void updateState(String comment, Closure edit)
    {
        XWikiContext xc = xc()
        XWikiDocument doc = xc.getWiki().getDocument(codeRef('State'), xc).clone()
        BaseObject obj = doc.getXObject(codeRef('StateClass'), true, xc)
        edit(obj)
        doc.setHidden(true)
        if (doc.isNew()) {
            doc.setTitle('Content Expiry state')
        }
        stamp(doc)
        xc.getWiki().saveDocument(doc, comment, true, xc)
    }

    // ------------------------------------------------------------------------------------------
    // Daily run: find pages that reached a warning threshold since the last run, email owners
    // ------------------------------------------------------------------------------------------

    /**
     * Runs once for the period since the last run. Called by the scheduler job, and can be
     * called by an admin (for example from a test page).
     *
     * A warning goes out when a page's expiry minus a threshold falls in the window since the
     * last completed run, so a missed day is caught up on the next run and nothing is sent twice.
     * The first window starts one second before the policy date so the first warning isn't lost.
     * Each person gets one email per run listing all their pages. If an email fails (mail server
     * down, bad address) the run still completes, so everyone else isn't emailed again, and the
     * failed person's pages are kept on the state page and retried on each later run for up to
     * RETRY_DAYS days. The result shown on the report page says what failed.
     *
     * @return [ran: boolean, message: text, pages: n, emails: n, failed: list of strings]
     */
    Map runDaily()
    {
        return runAt(new Date())
    }

    /** Same as runDaily with the clock set by the caller. Lets tests step through time. */
    Map runAt(Date now)
    {
        requireAdmin()
        Map s = readSettings()
        if (!s.active) {
            return [ran: false, message: 'Warnings are switched off, so nothing was sent.']
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return [ran: false, message: 'A run is already in progress.']
        }
        try {
            return doRun(s, now)
        } finally {
            RUNNING.set(false)
        }
    }

    /** What the scheduler job calls. Never throws: a failure is recorded for the report page. */
    Map runScheduled()
    {
        try {
            return runDaily()
        } catch (SecurityException e) {
            DocumentReference user = xc().getUserReference()
            String who = user != null ? serializeLocal(user) : 'nobody'
            String message = ("The daily job ran as ${who}, who is not a wiki administrator, so nothing was sent. " +
                'Open the Content expiry settings and click Save to run the job as yourself.').toString()
            LoggerFactory.getLogger('org.xwiki.contentexpiry').error(message)
            recordFailure(message)
            return [ran: false, message: message]
        } catch (Throwable e) {
            LoggerFactory.getLogger('org.xwiki.contentexpiry').error('Content Expiry daily run failed', e)
            recordFailure('The last run failed: ' + e.getMessage())
            return [ran: false, message: 'The run failed: ' + e.getMessage()]
        }
    }

    /** Keeps the reason a run failed on the state page, where the report shows it. Never throws. */
    private void recordFailure(String message)
    {
        try {
            updateState('Run failed') { BaseObject o ->
                o.setLargeStringValue('lastResult', message)
            }
        } catch (Throwable ignored) {
            // Nothing more we can do.
        }
    }

    private Map doRun(Map s, Date now)
    {
        Date policyDate = ensurePolicyDate(s, now)
        Map state = readState()
        Date floorStart = new Date(policyDate.getTime() - 1000L)
        Date from = (state.lastRun == null || state.lastRun.before(floorStart)) ? floorStart : state.lastRun
        if (!now.after(from)) {
            return [ran: false, message: 'Nothing to do: the last run was after this time.']
        }

        Policy p = policy(s, policyDate)
        Map cov = coverage(s)
        Map clause = filterClause('due', p, s, now)
        Map people = [:]
        int pages = 0
        int withChildren = 0
        int noRecipient = 0
        List<String> failed = []

        if (clause != null) {
            String where = cov.where + clause.where
            Map params = cov.params + clause.params
            int offset = 0
            while (true) {
                Query q = hql(("select doc.fullName, doc.date from XWikiDocument doc where ${where} " +
                    'order by doc.date asc, doc.fullName asc').toString(), params)
                q.setOffset(offset)
                q.setLimit(200)
                List rows = q.execute()
                if (rows.isEmpty()) {
                    break
                }
                for (Object[] r : rows) {
                    Date expiry = p.expiry((Date) r[1], now)
                    List<Integer> hit = p.crossed(expiry, from, now)
                    if (hit.isEmpty()) {
                        continue
                    }
                    Map row = row((String) r[0], (Date) r[1], p, s, now)
                    if (row.status == 'excluded') {
                        continue
                    }
                    if (row.hasChildren) {
                        withChildren++
                        continue
                    }
                    List<Map> addresses = addressesFor(row.contact)
                    if (addresses.isEmpty()) {
                        noRecipient++
                        continue
                    }
                    pages++
                    Map item = [title: row.title, reference: row.fullName, url: row.externalUrl,
                        lastModified: row.lastModified, lastModifiedText: mailDate(row.lastModified),
                        expiry: row.expiry, expiryText: mailDate(row.expiry), daysLeft: row.daysLeft,
                        threshold: Policy.minOf(hit), contactSource: row.contactSource, since: now.getTime()]
                    addresses.each { Map a -> addItem(people, a.email, a.name, item) }
                }
                offset += 200
            }
        }

        int retried = loadRetries(state.retry, people, p, s, now)

        int emails = 0
        List<String> stillFailing = []
        people.values().each { Map person ->
            Map result = sendDigest(s, person, now)
            if (result.ok) {
                emails++
            } else {
                failed << "${person.email}: ${result.error}".toString()
                person.items.each { Map item ->
                    stillFailing << [item.since, person.email, person.name.toString().replace('|', ' '),
                        item.reference, item.threshold].join('|')
                }
            }
        }

        StringBuilder text = new StringBuilder()
        if (pages == 0 && failed.isEmpty()) {
            text.append('No warnings were due.')
        } else {
            text.append("Warned about ${pages} page(s) in ${emails} email(s).".toString())
        }
        if (withChildren > 0) {
            text.append(" ${withChildren} page(s) with child pages were left for an admin.".toString())
        }
        if (noRecipient > 0) {
            text.append(" ${noRecipient} page(s) had nobody to email.".toString())
        }
        if (retried > 0) {
            text.append(" Included ${retried} page(s) retried from an earlier failure.".toString())
        }
        if (!failed.isEmpty()) {
            text.append(" ${failed.size()} email(s) failed and will be retried: ${failed.join('; ')}".toString())
        }
        String summary = text.toString()
        updateState('Run finished') { BaseObject o ->
            o.setDateValue('lastRun', now)
            o.setLargeStringValue('lastResult', summary)
            o.setLargeStringValue('retry', stillFailing.join('\n'))
        }
        return [ran: true, message: summary, pages: pages, emails: emails, failed: failed,
            withChildren: withChildren, noRecipient: noRecipient]
    }

    // ------------------------------------------------------------------------------------------
    // Email
    // ------------------------------------------------------------------------------------------

    /** Adds a page to a person's digest, once. If it is there already the more urgent threshold wins. */
    private static void addItem(Map people, String email, Object name, Map item)
    {
        Map person = people.get(email.toLowerCase(), [email: email, name: name, items: []])
        Map existing = person.items.find { it.reference == item.reference }
        if (existing == null) {
            person.items << item
        } else if (item.threshold < existing.threshold) {
            existing.threshold = item.threshold
        }
    }

    /**
     * Puts warnings that failed on an earlier run back into this run's digests. Entries are lines
     * of since|email|name|page|threshold. A page that has since been kept, deleted or excluded, or
     * is no longer close to expiry, is dropped, as is anything older than RETRY_DAYS.
     *
     * @return how many pages were added back
     */
    private int loadRetries(String text, Map people, Policy p, Map s, Date now)
    {
        int added = 0
        XWikiContext xc = xc()
        long oldest = Policy.addDays(now, -RETRY_DAYS).getTime()
        (text ?: '').split('\n').each { String line ->
            List<String> f = line.trim().split('\\|', 5) as List
            if (f.size() != 5 || !(f[0] ==~ /\d+/) || f[0].toLong() < oldest) {
                return
            }
            DocumentReference ref = resolve(f[3])
            XWikiDocument doc = xc.getWiki().getDocument(ref, xc)
            if (doc.isNew()) {
                return
            }
            Date expiry = p.expiry(doc.getDate(), now)
            if (p.daysLeft(expiry, now) > Policy.maxWarning(s.warningDays) || !isCovered(ref, doc, s) || hasChildren(doc)) {
                return
            }
            Map row = row(f[3], doc.getDate(), p, s, now)
            Map item = [title: row.title, reference: row.fullName, url: row.externalUrl,
                lastModified: row.lastModified, lastModifiedText: mailDate(row.lastModified),
                expiry: row.expiry, expiryText: mailDate(row.expiry), daysLeft: row.daysLeft,
                threshold: f[4].toInteger(), contactSource: row.contactSource, since: f[0].toLong()]
            addItem(people, f[1], f[2], item)
            added++
        }
        return added
    }

    /**
     * Addresses for a contact: a plain address, a user, or every member of a group. People who
     * can't be emailed (disabled, no address) are left out.
     */
    private List<Map> addressesFor(String contact)
    {
        if (!contact) {
            return []
        }
        if (contact.contains('@')) {
            return [[email: contact, name: contact]]
        }
        XWikiContext xc = xc()
        DocumentReference ref = resolve(contact)
        if (!xc.getWiki().exists(ref, xc)) {
            return []
        }
        List<DocumentReference> users = []
        XWikiDocument doc = xc.getWiki().getDocument(ref, xc)
        if (doc.getXObject(userClassRef(ref)) != null) {
            users << ref
        } else {
            users.addAll(Utils.getComponent(GroupManager).getMembers(ref, true))
        }
        List<Map> out = []
        users.each { DocumentReference u ->
            if (userProblem(u) == null) {
                XWikiDocument ud = xc.getWiki().getDocument(u, xc)
                BaseObject obj = ud.getXObject(userClassRef(u))
                String name = ((obj.getStringValue('first_name') ?: '') + ' ' + (obj.getStringValue('last_name') ?: '')).trim()
                out << [email: obj.getStringValue('email').trim(), name: name ?: u.getName()]
            }
        }
        return out
    }

    private DocumentReference userClassRef(DocumentReference userRef)
    {
        return new DocumentReference(userRef.getWikiReference().getName(), 'XWiki', 'XWikiUsers')
    }

    /** The wiki's display name. The 'title' preference is itself a Velocity template, so don't use it. */
    private String wikiName()
    {
        try {
            String pretty = Utils.getComponent(WikiDescriptorManager).getById(xc().getWikiId())?.getPrettyName()
            return pretty ?: 'the wiki'
        } catch (Exception e) {
            return 'the wiki'
        }
    }

    private static String mailDate(Date date)
    {
        return new java.text.SimpleDateFormat('d MMMM yyyy', Locale.ENGLISH).format(date)
    }

    /** One digest email. Returns [ok: true] or [ok: false, error: reason]. */
    private Map sendDigest(Map s, Map person, Date now)
    {
        try {
            def mail = Utils.getComponent(ScriptServiceManager).get('mail.sender')
            XWikiContext xc = xc()
            Map vars = [
                recipientName: person.name, items: person.items, count: person.items.size(),
                mode: s.mode, retentionMonths: s.retentionMonths,
                wikiName: wikiName(),
                wikiUrl: xc.getWiki().getExternalURL('Main.WebHome', 'view', xc)
            ]
            def message = mail.createMessage('template', codeRef('MailTemplate'), [language: 'en', velocityVariables: vars])
            if (message == null) {
                return [ok: false, error: 'the email template could not be loaded']
            }
            message.setFrom(new InternetAddress(s.effectiveSender))
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(person.email))
            def result = mail.send(message)
            result.statusResult.waitTillProcessed(60000L)
            List errors = result.statusResult.getAllErrors().collect { it.errorSummary }
            if (!result.isProcessed()) {
                return [ok: false, error: 'timed out waiting for the mail server']
            }
            return errors.isEmpty() ? [ok: true] : [ok: false, error: errors.join(', ')]
        } catch (Exception e) {
            return [ok: false, error: e.getMessage() ?: e.getClass().getSimpleName()]
        }
    }

    // ------------------------------------------------------------------------------------------
    // The scheduler job runs as the admin who last saved the settings
    // ------------------------------------------------------------------------------------------

    /**
     * ContentExpiry.Code.Job is a Groovy scheduler job. The scheduler runs it as the user stored
     * on the job, so after each settings save that is set to the saving admin. The engine checks
     * admin rights on every run, so the job stops warning if that account loses them.
     * The job page's author is left alone: it was saved by a user with programming rights.
     */
    private void syncJob()
    {
        XWikiContext xc = xc()
        DocumentReference jobClass = new DocumentReference(xc.getWikiId(), 'XWiki', 'SchedulerJobClass')
        XWikiDocument current = xc.getWiki().getDocument(codeRef('Job'), xc)
        BaseObject job = current.getXObject(jobClass)
        String user = serializeLocal(xc.getUserReference())
        if (job != null && job.getStringValue('contextUser') != user) {
            XWikiDocument doc = current.clone()
            doc.getXObject(jobClass).setStringValue('contextUser', user)
            xc.getWiki().saveDocument(doc, "Runs as ${user}".toString(), true, xc)
        }
        // Always, not only when the page changed: see reloadJob.
        reloadJob()
    }

    /**
     * The scheduler captures the user a job runs as at the moment the job is scheduled, so changing
     * the job page has no effect until the job is reloaded. An import also puts the placeholder
     * user back on the page and reschedules with it. Reloading keeps the job's status and cron time.
     * Checked on 17.10.14.
     */
    private void reloadJob()
    {
        try {
            XWikiContext xc = xc()
            DocumentReference jobClass = new DocumentReference(xc.getWikiId(), 'XWiki', 'SchedulerJobClass')
            BaseObject job = xc.getWiki().getDocument(codeRef('Job'), xc).getXObject(jobClass)
            if (job != null) {
                xc.getWiki().getPlugin('scheduler', xc).reloadJob(job, xc)
            }
        } catch (Throwable e) {
            LoggerFactory.getLogger('org.xwiki.contentexpiry').warn('Could not reload the Content Expiry job: {}', e.getMessage())
        }
    }

    /** Whether the user stored on the job page is an administrator, which the daily run needs. */
    boolean isJobUserAdmin()
    {
        requireAdmin()
        String user = getJobUser()
        return user ? isAdminUser(resolve(user)) : false
    }

    /** Who the scheduled job runs as, or an empty string if the job page is missing. */
    String getJobUser()
    {
        requireAdmin()
        XWikiContext xc = xc()
        BaseObject job = xc.getWiki().getDocument(codeRef('Job'), xc)
            .getXObject(new DocumentReference(xc.getWikiId(), 'XWiki', 'SchedulerJobClass'))
        return job != null ? job.getStringValue('contextUser') : ''
    }

    // ------------------------------------------------------------------------------------------
    // For ordinary users: banner, Keep and owner. Each checks the user's own rights on the page.
    // ------------------------------------------------------------------------------------------

    /** Whether the current user has a right on a page. A null user is the guest, which the API expects. */
    private boolean may(Right right, DocumentReference ref)
    {
        return Utils.getComponent(AuthorizationManager).hasAccess(right, xc().getUserReference(), ref)
    }

    private boolean isSignedIn()
    {
        DocumentReference user = xc().getUserReference()
        return user != null && user.getName() != 'XWikiGuest'
    }

    /** Whether a page is covered at all: the same rules as the report, applied to one page. */
    private boolean isCovered(DocumentReference ref, XWikiDocument doc, Map s)
    {
        Map cov = coverage(s)
        if (count(cov.where + ' and doc.fullName = :ceSelf', cov.params + [ceSelf: serializeLocal(ref)]) == 0) {
            return false
        }
        return !isExtensionPage(ref) && doc.getXClass().getPropertyList().isEmpty()
    }

    /**
     * What to show on a page that is inside its warning window, or null for no banner. Null also
     * when the page is excluded, has child pages, can't be viewed by this user, or warnings are off.
     */
    Map bannerFor(DocumentReference ref)
    {
        Map s = readSettings()
        if (!s.active || !may(Right.VIEW, ref)) {
            return null
        }
        Date policyDate = readState().policyDate
        XWikiContext xc = xc()
        XWikiDocument doc = xc.getWiki().getDocument(ref, xc)
        if (policyDate == null || doc.isNew()) {
            return null
        }
        Date now = new Date()
        Policy p = policy(s, policyDate)
        Date expiry = p.expiry(doc.getDate(), now)
        long left = p.daysLeft(expiry, now)
        if (left > Policy.maxWarning(s.warningDays) || !isCovered(ref, doc, s) || hasChildren(doc)) {
            return null
        }
        return [expiry: expiry, daysLeft: left, status: p.status(expiry, now), lastModified: doc.getDate(),
            retentionMonths: s.retentionMonths, canKeep: isSignedIn() && may(Right.EDIT, ref), mode: s.mode]
    }

    /**
     * Keeps a page by saving it as a minor edit, which restarts its clock and leaves a line in
     * its history. Needs edit right on the page. Changes the page's author, not its content author.
     */
    Map keep(DocumentReference ref)
    {
        if (!isSignedIn() || !may(Right.EDIT, ref)) {
            return [ok: false, error: 'You need edit rights on this page to keep it.']
        }
        XWikiContext xc = xc()
        XWikiDocument doc = xc.getWiki().getDocument(ref, xc).clone()
        if (doc.isNew()) {
            return [ok: false, error: 'This page no longer exists.']
        }
        doc.setAuthorReference(xc.getUserReference())
        // XWiki ignores a save that changes nothing. This is what makes it save and restart the clock.
        doc.setMetaDataDirty(true)
        xc.getWiki().saveDocument(doc, 'Kept by Content Expiry', true, xc)
        return [ok: true]
    }

    /**
     * Facts for the Expiry tab on a page: the owner, and whether and when the page would expire.
     * Details of who would be warned are only given to people who can edit the page.
     */
    Map pageInfo(DocumentReference ref)
    {
        if (!may(Right.VIEW, ref)) {
            return null
        }
        Map s = readSettings()
        XWikiContext xc = xc()
        XWikiDocument doc = xc.getWiki().getDocument(ref, xc)
        Date now = new Date()
        Date policyDate = s.active ? readState().policyDate : null
        Policy p = policy(s, policyDate)
        boolean canEdit = isSignedIn() && may(Right.EDIT, ref)
        BaseObject owner = doc.getXObject(codeRef('OwnerClass'))

        Map info = [active: s.active, mode: s.mode, retentionMonths: s.retentionMonths, canEdit: canEdit,
            lastModified: doc.getDate(), owner: owner != null ? owner.getStringValue('owner') : '']
        info.covered = !doc.isNew() && isCovered(ref, doc, s)
        if (info.covered) {
            Date expiry = p.expiry(doc.getDate(), now)
            info.expiry = expiry
            info.daysLeft = p.daysLeft(expiry, now)
            info.hasChildren = hasChildren(doc)
            if (canEdit) {
                Map contact = contactFor(doc, s)
                info.contact = contact.name
                info.contactSource = contact.source
            }
        }
        return info
    }

    /**
     * Sets or clears a page's owner. Needs edit right on the page. An empty value clears it.
     * Saving counts as a change to the page, so it restarts the clock.
     *
     * @return [ok: true] or [ok: false, error: text]
     */
    Map setOwner(DocumentReference ref, String ownerText)
    {
        if (!isSignedIn() || !may(Right.EDIT, ref)) {
            return [ok: false, error: 'You need edit rights on this page to change its owner.']
        }
        XWikiContext xc = xc()
        XWikiDocument doc = xc.getWiki().getDocument(ref, xc).clone()
        if (doc.isNew()) {
            return [ok: false, error: 'This page no longer exists.']
        }
        String text = (ownerText ?: '').trim()
        if (text) {
            DocumentReference userRef = resolve(text.contains('.') ? text : 'XWiki.' + text)
            String problem = userProblem(userRef)
            if (problem != null) {
                return [ok: false, error: "${text} can't be the owner: ${problem}.".toString()]
            }
            doc.getXObject(codeRef('OwnerClass'), true, xc).setStringValue('owner', serializeLocal(userRef))
        } else {
            BaseObject existing = doc.getXObject(codeRef('OwnerClass'))
            if (existing != null) {
                doc.removeXObject(existing)
            }
        }
        doc.setAuthorReference(xc.getUserReference())
        doc.setMetaDataDirty(true)
        xc.getWiki().saveDocument(doc, text ? 'Owner set by Content Expiry' : 'Owner cleared by Content Expiry', true, xc)
        return [ok: true]
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

        /** Smallest of the given numbers, or 0 for an empty list. */
        static int minOf(java.util.List<Integer> numbers)
        {
            return minWarning(numbers);
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

        /**
         * Warning thresholds whose moment (expiry minus that many days) falls after from and up to
         * and including to. Largest first. The caller keeps from and to contiguous between runs, so
         * every threshold is reported exactly once.
         */
        java.util.List<Integer> crossed(java.util.Date expiry, java.util.Date from, java.util.Date to)
        {
            java.util.List<Integer> hit = new java.util.ArrayList<Integer>();
            for (Integer days : this.warningDays) {
                java.util.Date moment = addDays(expiry, -days.intValue());
                if (moment.after(from) && !moment.after(to)) {
                    hit.add(days);
                }
            }
            return hit;
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
