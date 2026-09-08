package com.axonect.ee.enterpriseintegration.domain.constant;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDumpSqlTest {

    private static final Timestamp DAY_START = Timestamp.valueOf(LocalDate.of(2026, 8, 22).atStartOfDay());
    private static final Timestamp DAY_END = Timestamp.valueOf(LocalDate.of(2026, 8, 23).atStartOfDay());

    private UserDumpSql.Statement build(String from, String to) {
        return UserDumpSql.build(from, to, DAY_START, DAY_END,
                "BANDWIDTH", "DATA", "Unlimited", "DD/MM/YYYY HH24:MI:SS");
    }

    @Test
    void bindsParametersInThePlaceholderOrder() {
        UserDumpSql.Statement statement = build("a", "m");

        assertEquals(countPlaceholders(statement.sql()), statement.params().size(),
                "every ? must have a value bound to it");

        // svc window, svc range, the four bucket pivots, then the mac and outer ranges.
        assertEquals(List.of(DAY_END, DAY_START, "a", "m",
                        "BANDWIDTH", "DATA", "Unlimited", "DATA",
                        "a", "m", "a", "m"),
                statement.params());
    }

    @Test
    void unboundedShardOmitsTheRangePredicatesEntirely() {
        UserDumpSql.Statement statement = build(null, null);

        assertEquals(countPlaceholders(statement.sql()), statement.params().size());
        assertEquals(List.of(DAY_END, DAY_START, "BANDWIDTH", "DATA", "Unlimited", "DATA"),
                statement.params());
        assertFalse(statement.sql().contains("USER_NAME >="),
                "an unbounded dump should not carry a range predicate at all");
    }

    @Test
    void openEndedShardBindsOnlyTheBoundItHas() {
        assertEquals(List.of(DAY_END, DAY_START, "t", "BANDWIDTH", "DATA", "Unlimited", "DATA", "t", "t"),
                build("t", null).params());
        assertEquals(List.of(DAY_END, DAY_START, "g", "BANDWIDTH", "DATA", "Unlimited", "DATA", "g", "g"),
                build(null, "g").params());
    }

    @Test
    void selectsTheColumnsTheReaderIndexesInto() {
        String sql = build(null, null).sql();

        assertEquals(1, UserDumpSql.COL_USER_ID);
        assertTrue(sql.contains("bkt.QUOTA_BUCKET_ID"),
                "the quota bucket drives the usage lookup and must be selected");
        assertEquals(UserDumpSql.COL_BUNDLE_DEACTIVATION_DATE + 1, UserDumpSql.COL_QUOTA_BUCKET_ID,
                "the helper column follows the last CSV-mapped column");
    }

    @Test
    void aggregatesTheSatelliteTablesOncePerUserRatherThanPerRow() {
        String sql = build(null, null).sql();

        assertTrue(sql.contains("LISTAGG(m.MAC_ADDRESS"), "MAC addresses must be collapsed in SQL");
        assertTrue(sql.contains("LISTAGG(m.ORIGINAL_MAC_ADDRESS"));
        assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY si.USERNAME"),
                "the active bundle must be picked analytically, not by a correlated sub-select");
        assertTrue(sql.contains("GROUP BY b.SERVICE_ID"), "buckets must be pivoted in one pass");
        assertTrue(sql.contains("JOIN svc ON svc.ID = b.SERVICE_ID"),
                "the bucket scan must be pruned to the shard's services");
    }

    @Test
    void ordersByUsernameSoTheUsageStreamCanBeMergedIn() {
        assertTrue(build(null, null).sql().trim().endsWith("ORDER BY u.USER_NAME"));
    }

    @Test
    void restrictsTheBundleToTheReportedDay() {
        String sql = build(null, null).sql();

        assertTrue(sql.contains("si.SERVICE_START_DATE < ?"));
        assertTrue(sql.contains("si.EXPIRY_DATE >= ?"));
    }

    @Test
    void appliesTheConfiguredDateFormatToEveryTimestamp() {
        String sql = UserDumpSql.build(null, null, DAY_START, DAY_END,
                "BANDWIDTH", "DATA", "Unlimited", "YYYY-MM-DD HH24:MI:SS").sql();

        assertEquals(5, countOccurrences(sql, "'YYYY-MM-DD HH24:MI:SS'"),
                "created, updated, customer activation, bundle activation and deactivation dates");
    }

    @Test
    void rejectsADateFormatThatCouldCarrySql() {
        assertThrows(IllegalArgumentException.class, () -> UserDumpSql.build(null, null, DAY_START, DAY_END,
                "BANDWIDTH", "DATA", "Unlimited", "YYYY') || (SELECT PASSWORD FROM AAA_USER) || ('"));
        assertThrows(IllegalArgumentException.class, () -> UserDumpSql.build(null, null, DAY_START, DAY_END,
                "BANDWIDTH", "DATA", "Unlimited", "  "));
    }

    private static int countPlaceholders(String sql) {
        return countOccurrences(sql, "?");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
