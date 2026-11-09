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
}
