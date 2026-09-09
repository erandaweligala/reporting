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

    private static final String DATE_FORMAT = "YYYY-MM-DD HH24:MI:SS.FF3";

    private SqlStatement build(String from, String to) {
        return UserDumpSql.build(from, to, DAY_START, DAY_END, "BANDWIDTH", "DATA", DATE_FORMAT);
    }

    @Test
    void bindsParametersInThePlaceholderOrder() {
        SqlStatement statement = build("a", "m");

        assertEquals(countPlaceholders(statement.sql()), statement.params().size(),
                "every ? must have a value bound to it");

        // svc window, svc range, the three bucket pivots, then the mac and outer ranges.
        assertEquals(List.of(DAY_END, DAY_START, "a", "m",
                        "BANDWIDTH", "DATA", "DATA",
                        "a", "m", "a", "m"),
                statement.params());
    }

    @Test
    void unboundedShardOmitsTheRangePredicatesEntirely() {
        SqlStatement statement = build(null, null);

        assertEquals(countPlaceholders(statement.sql()), statement.params().size());
        assertEquals(List.of(DAY_END, DAY_START, "BANDWIDTH", "DATA", "DATA"),
                statement.params());
        assertFalse(statement.sql().contains("USER_NAME >="),
                "an unbounded dump should not carry a range predicate at all");
    }

    @Test
    void openEndedShardBindsOnlyTheBoundItHas() {
        assertEquals(List.of(DAY_END, DAY_START, "t", "BANDWIDTH", "DATA", "DATA", "t", "t"),
                build("t", null).params());
        assertEquals(List.of(DAY_END, DAY_START, "g", "BANDWIDTH", "DATA", "DATA", "g", "g"),
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
        assertEquals(UserDumpSql.COL_VLAN_ID + 1, UserDumpSql.COL_NOTIFICATION_TEMPLATES,
                "the statement selects nothing for NAS_IP_ADDRESS, so the columns either side "
                        + "of that CSV position are adjacent in the result set");
    }

    @Test
    void reportsTheUsernameUnderSlmnBecauseTheTableHasNoSuchColumn() {
        String sql = build(null, null).sql();

        assertFalse(sql.contains("u.SLMN"), "AAA_USER has no SLMN column to select");
        // SLMN sits between UPDATED_DATE and VLAN_ID in the agreed column order, and the username
        // is bound into that position.
        assertTrue(sql.contains("u.USER_NAME AS SLMN, u.VLAN_ID,"),
                "the username must be selected again in the SLMN position");
    }

    @Test
    void leavesTheNasIpAddressToTheElasticsearchLookup() {
        String sql = build(null, null).sql();

        assertFalse(sql.contains("NAS_IP_ADDRESS"),
                "the NAS address comes from the CDR session documents, not from AAA_USER");
        assertTrue(sql.contains("u.NAS_PORT_TYPE"),
                "the similarly named NAS_PORT_TYPE column is a real one and must stay");
    }

    @Test
    void aggregatesTheBundleAndItsBucketsOncePerUserRatherThanPerRow() {
        String sql = build(null, null).sql();

        assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY si.USERNAME"),
                "the active bundle must be picked analytically, not by a correlated sub-select");
        assertTrue(sql.contains("GROUP BY b.SERVICE_ID"), "buckets must be pivoted in one pass");
        assertTrue(sql.contains("JOIN svc ON svc.ID = b.SERVICE_ID"),
                "the bucket scan must be pruned to the shard's services");
    }

    @Test
    void carriesNothingThatDependsOnTheServerVersionToParse() {
        String sql = build("a", "m").sql();

        // Every run of this report failed with ORA-00907 while the MAC addresses were collapsed
        // with LISTAGG: first over its 12.2-only ON OVERFLOW clause, then over LISTAGG itself,
        // which the server reaching the WITHIN rejects the same way. Nothing in the statement may
        // be newer than the parser that rejected it — a construct that needs a particular version
        // fails the whole dump, not one column.
        assertFalse(sql.contains("LISTAGG"), "LISTAGG is what the server rejects with ORA-00907");
        assertFalse(sql.contains("WITHIN GROUP"));
        assertFalse(sql.contains("ON OVERFLOW"));
        assertEquals(countOccurrences(sql, "("), countOccurrences(sql, ")"),
                "an unbalanced statement is the other way to earn ORA-00907");
    }

    @Test
    void handsOutOneRowPerMacAddressForTheReaderToJoinUp() {
        String sql = build("a", "m").sql();

        assertTrue(sql.contains("LEFT JOIN AAA_USER_MAC_ADDRESS mac ON mac.USER_NAME = u.USER_NAME"),
                "the MAC addresses must be joined row for row rather than aggregated");
        assertTrue(sql.contains("u.IPV6, mac.MAC_ADDRESS,"), "MAC_ADDRESS keeps its column position");
        assertTrue(sql.contains("mac.ORIGINAL_MAC_ADDRESS, u.REMOTE_ID"),
                "ORIGINAL_MAC_ADDRESS keeps its column position");
    }

    @Test
    void boundsTheMacJoinInsideItsOwnConditionSoUsersWithoutOneSurvive() {
        String sql = build("a", "m").sql();

        // In the WHERE clause the same predicate would discard the outer join's null rows, and
        // with them every user who holds no MAC address at all.
        assertTrue(sql.contains("ON mac.USER_NAME = u.USER_NAME AND mac.USER_NAME >= ? "
                        + "AND mac.USER_NAME < ? WHERE"),
                "the shard bounds belong to the join condition, not the WHERE clause");
    }

    @Test
    void ordersByUsernameSoTheUsageStreamCanBeMergedIn() {
        // The id is what puts a user's own rows in the order their addresses are listed in; the
        // username is what the Elasticsearch stream is merged on.
        assertTrue(build(null, null).sql().trim().endsWith("ORDER BY u.USER_NAME, mac.ID"));
    }

    @Test
    void restrictsTheBundleToTheReportedDay() {
        String sql = build(null, null).sql();

        assertTrue(sql.contains("si.SERVICE_START_DATE < ?"));
        assertTrue(sql.contains("si.EXPIRY_DATE >= ?"));
    }

    @Test
    void appliesTheConfiguredDateFormatToEveryTimestamp() {
        String sql = build(null, null).sql();

        assertEquals(5, countOccurrences(sql, "'" + DATE_FORMAT + "'"),
                "created, updated, customer activation, bundle activation and deactivation dates");
    }

    @Test
    void castsEveryTimestampSoAFractionalSecondsModelIsLegalOnDateColumns() {
        String sql = build(null, null).sql();

        // TO_CHAR of a DATE with an FF element raises ORA-01821, and the dump's format carries
        // milliseconds, so every timestamp has to reach TO_CHAR as a TIMESTAMP.
        assertEquals(5, countOccurrences(sql, "AS TIMESTAMP)"));
        // CUSTOMER_ACTIVATION_DATE has no column of its own on AAA_USER either: the dump reports
        // CREATED_DATE in that position, which is why the created date is rendered twice.
        assertEquals(2, countOccurrences(sql, "TO_CHAR(CAST(u.CREATED_DATE AS TIMESTAMP)"));
        for (String column : List.of("u.CREATED_DATE", "u.UPDATED_DATE",
                "svc.SERVICE_START_DATE", "svc.EXPIRY_DATE")) {
            assertTrue(sql.contains("TO_CHAR(CAST(" + column + " AS TIMESTAMP), '" + DATE_FORMAT + "')"),
                    column + " must be rendered under the configured format model");
        }
    }

    @Test
    void anUnlimitedQuotaBucketLeavesTheQuotaColumnEmpty() {
        String sql = build(null, null).sql();

        // The consuming system reads an empty QUOTA as "no cap", so an unlimited bucket must not
        // reach the pivot at all rather than being labelled.
        assertTrue(sql.contains("MAX(CASE WHEN b.BUCKET_TYPE = ? AND NVL(b.IS_UNLIMITED, 0) <> 1"),
                "unlimited buckets must be excluded from the QUOTA pivot");
        assertFalse(sql.contains("IS_UNLIMITED = 1 THEN ?"),
                "no label may be bound in for an unlimited quota");
    }

    @Test
    void rejectsADateFormatThatCouldCarrySql() {
        assertThrows(IllegalArgumentException.class, () -> UserDumpSql.build(null, null, DAY_START, DAY_END,
                "BANDWIDTH", "DATA", "YYYY') || (SELECT PASSWORD FROM AAA_USER) || ('"));
        assertThrows(IllegalArgumentException.class, () -> UserDumpSql.build(null, null, DAY_START, DAY_END,
                "BANDWIDTH", "DATA", "  "));
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
