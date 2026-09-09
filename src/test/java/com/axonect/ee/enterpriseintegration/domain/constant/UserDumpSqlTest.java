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
    void collapsesTheMacListsWithoutSyntaxOnlyANewerOracleParses() {
        String sql = build(null, null).sql();

        // LISTAGG's own ON OVERFLOW clause is 12.2 and later. An older server reaches the ON where
        // it expects the closing bracket and rejects the entire statement with ORA-00907, so the
        // dump fails outright instead of truncating a MAC list.
        assertFalse(sql.contains("ON OVERFLOW"),
                "the overflow clause does not parse before Oracle 12.2 and fails the whole dump");
        assertTrue(sql.contains("LISTAGG(m.MAC_ADDRESS, ',')"));
        assertTrue(sql.contains("LISTAGG(m.ORIGINAL_MAC_ADDRESS, ',')"));
    }

    @Test
    void dropsTheMacRowsThatWouldTakeAListPastWhatListaggCanReturn() {
        String sql = build(null, null).sql();

        // What the overflow clause used to do, done where every Oracle understands it: a running
        // total of the bytes each row would contribute decides which rows reach the aggregate.
        assertTrue(sql.contains("ROWS UNBOUNDED PRECEDING) AS LIST_BYTES"),
                "the byte budget must be accumulated in the order the addresses are listed in");
        assertTrue(sql.contains("WHERE m.LIST_BYTES <= 4000"),
                "rows past the VARCHAR2 budget must be dropped before LISTAGG sees them");
        // Charging each row the wider of its two addresses cuts both lists at the same row, so the
        // MAC and original-MAC columns stay row-aligned.
        assertTrue(sql.contains("SUM(GREATEST(NVL(LENGTHB(ma.MAC_ADDRESS), 0),"));
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
