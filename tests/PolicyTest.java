import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Tests for the engine's Policy class. Run with tests/run_policy_tests.py, which extracts
 * Policy from Engine.groovy. No test framework needed.
 */
public class PolicyTest
{
    private static int failures = 0;

    private static int passes = 0;

    public static void main(String[] args)
    {
        parsesWarningDays();
        expiryIsRetentionAgeWhenFarFromFloor();
        floorProtectsOldPagesWhenWarningsStartToday();
        floorUsesPolicyDateOnceWarningsAreOn();
        cutoffMatchesExpiryAcrossMonthEnds();
        cutoffIsNullWhileFloorIsAhead();
        daysLeftRoundsUp();
        statusBands();
        crossedFindsEachThresholdOnce();
        crossedStartsAtPolicyDate();
        crossedFollowsAPageThatMoves();

        System.out.println(passes + " passed, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static Date at(int y, int m, int d, int h, int min)
    {
        return Date.from(LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.systemDefault()).toInstant());
    }

    private static Date at(int y, int m, int d)
    {
        return at(y, m, d, 12, 0);
    }

    private static void check(String name, Object expected, Object actual)
    {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passes++;
        } else {
            failures++;
            System.out.println("FAIL " + name + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static List<Integer> days(Integer... values)
    {
        return Arrays.asList(values);
    }

    static void parsesWarningDays()
    {
        check("default when empty", days(60, 30, 7), Policy.parseWarningDays(""));
        check("default when null", days(60, 30, 7), Policy.parseWarningDays(null));
        check("sorted largest first, duplicates removed", days(90, 30, 7), Policy.parseWarningDays("7, 30,90 30"));
        check("junk ignored", days(14), Policy.parseWarningDays("14, two, -3, 0"));
        check("only junk gives default", days(60, 30, 7), Policy.parseWarningDays("soon"));
        check("max", 90, Policy.maxWarning(days(90, 30, 7)));
        check("min", 7, Policy.minWarning(days(90, 30, 7)));
    }

    static void expiryIsRetentionAgeWhenFarFromFloor()
    {
        Policy p = new Policy(60, days(60, 30, 7), null);
        Date now = at(2026, 11, 9);
        check("5 years after last change", at(2028, 3, 1), p.expiry(at(2023, 3, 1), now));
    }

    static void floorProtectsOldPagesWhenWarningsStartToday()
    {
        Policy p = new Policy(60, days(60, 30, 7), null);
        Date now = at(2026, 11, 9);
        Date floor = Policy.addDays(now, 60);
        check("floor is today plus longest warning", floor, p.floorDate(now));
        check("10-year-old page waits for the floor", floor, p.expiry(at(2016, 1, 1), now));
        check("page reaching retention just after the floor keeps its own date",
            at(2027, 2, 1), p.expiry(at(2022, 2, 1), now));
    }

    static void floorUsesPolicyDateOnceWarningsAreOn()
    {
        Date policyDate = at(2026, 11, 1);
        Policy p = new Policy(60, days(60, 30, 7), policyDate);
        Date now = at(2027, 6, 1);
        check("floor fixed at policy date plus 60 days", at(2026, 12, 31), p.floorDate(now));
        check("old page expired at the floor, not today", at(2026, 12, 31), p.expiry(at(2015, 5, 5), now));
    }

    static void cutoffMatchesExpiryAcrossMonthEnds()
    {
        // For every 'by' date over two years, the cutoff must be the latest last-modified
        // date (at day resolution) whose retention age falls on or before 'by'.
        Policy p = new Policy(1, days(7), at(2000, 1, 1));
        Date by = at(2024, 1, 1);
        for (int i = 0; i < 730; i++) {
            Date cutoff = p.retentionCutoff(by);
            boolean cutoffReaches = !Policy.addMonths(cutoff, 1).after(by);
            boolean nextDayDoesNot = Policy.addMonths(Policy.addDays(cutoff, 1), 1).after(by);
            if (!cutoffReaches || !nextDayDoesNot) {
                check("cutoff for " + by, "latest qualifying day", "cutoff " + cutoff);
                return;
            }
            by = Policy.addDays(by, 1);
        }
        check("cutoff consistent for 730 days", true, true);

        Policy five = new Policy(60, days(60, 30, 7), at(2000, 1, 1));
        check("leap day: 29 Feb 2020 reaches 5 years on 28 Feb 2025",
            true, !Policy.addMonths(at(2020, 2, 29), 60).after(at(2025, 2, 28)));
        check("cutoff for 28 Feb 2025 includes 29 Feb 2020",
            false, five.retentionCutoff(at(2025, 2, 28)).before(at(2020, 2, 29)));
    }

    static void cutoffIsNullWhileFloorIsAhead()
    {
        Policy p = new Policy(60, days(60, 30, 7), null);
        Date now = at(2026, 11, 9);
        check("nothing expires within 30 days when warnings start today",
            null, p.lastModifiedCutoff(Policy.addDays(now, 30), now));
        check("but within 60 days the old pages appear",
            Policy.addMonths(Policy.addDays(now, 60), -60), p.lastModifiedCutoff(Policy.addDays(now, 60), now));
    }

    static void daysLeftRoundsUp()
    {
        Policy p = new Policy(60, days(60, 30, 7), null);
        Date now = at(2026, 11, 9, 12, 0);
        check("one hour left is one day", 1L, p.daysLeft(at(2026, 11, 9, 13, 0), now));
        check("exactly one day", 1L, p.daysLeft(at(2026, 11, 10, 12, 0), now));
        check("a day and a minute is two", 2L, p.daysLeft(at(2026, 11, 10, 12, 1), now));
        check("now is zero", 0L, p.daysLeft(now, now));
        check("a day and a half ago is minus one", -1L, p.daysLeft(at(2026, 11, 8, 0, 0), now));
    }

    static void statusBands()
    {
        Policy p = new Policy(60, days(60, 30, 7), null);
        Date now = at(2026, 11, 9);
        check("past", "expired", p.status(at(2026, 11, 1), now));
        check("now counts as expired", "expired", p.status(now, now));
        check("within 7 days", "due", p.status(Policy.addDays(now, 7), now));
        check("within 60 days", "warning", p.status(Policy.addDays(now, 8), now));
        check("edge of 60 days", "warning", p.status(Policy.addDays(now, 60), now));
        check("beyond 60 days", "ok", p.status(Policy.addDays(now, 61), now));
    }

    static void crossedFindsEachThresholdOnce()
    {
        Policy p = new Policy(60, days(60, 30, 7), null);
        Date expiry = at(2027, 1, 1);
        // Thresholds fall on 2 Nov, 2 Dec and 25 Dec 2026.
        check("nothing before the first", 0, p.crossed(expiry, at(2026, 10, 1), at(2026, 11, 1)).size());
        check("first threshold", days(60), p.crossed(expiry, at(2026, 11, 1), at(2026, 11, 3)));
        check("window start is exclusive", 0, p.crossed(expiry, at(2026, 11, 2), at(2026, 11, 3)).size());
        check("window end is inclusive", days(30), p.crossed(expiry, at(2026, 12, 1), at(2026, 12, 2)));
        check("missed days catch up", days(30, 7), p.crossed(expiry, at(2026, 12, 1), at(2026, 12, 31)));
        check("all three in one long gap", days(60, 30, 7), p.crossed(expiry, at(2026, 10, 1), at(2027, 1, 5)));
        // Consecutive daily windows never repeat or skip a threshold.
        int total = 0;
        Date from = at(2026, 10, 1);
        for (int i = 1; i <= 120; i++) {
            Date to = Policy.addDays(from, 1);
            total += p.crossed(expiry, from, to).size();
            from = to;
        }
        check("daily windows see each threshold once", 3, total);
    }

    static void crossedStartsAtPolicyDate()
    {
        // Floor-bound pages expire exactly policy date + longest warning, so the first warning
        // is due at the policy date itself. The engine starts the first window one second early.
        Date policy = at(2026, 11, 9, 9, 0);
        Policy p = new Policy(60, days(60, 30, 7), policy);
        Date expiry = p.expiry(at(2019, 1, 1), policy);
        Date oneSecondBefore = new Date(policy.getTime() - 1000L);
        check("first warning at policy date", days(60), p.crossed(expiry, oneSecondBefore, policy));
        check("missed without the extra second", 0, p.crossed(expiry, policy, Policy.addDays(policy, 1)).size());
    }

    static void crossedFollowsAPageThatMoves()
    {
        Policy p = new Policy(60, days(60, 30, 7), null);
        Date now = at(2026, 11, 9);
        // A page edited today expires in 60 months, far outside every warning.
        Date expiry = p.expiry(now, now);
        check("freshly edited page has nothing to warn", 0, p.crossed(expiry, at(2026, 11, 1), now).size());
    }
}
