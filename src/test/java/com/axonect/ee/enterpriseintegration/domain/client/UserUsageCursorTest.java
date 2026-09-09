package com.axonect.ee.enterpriseintegration.domain.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserUsageCursorTest {

    /** A cursor over a fixed, username-ordered list, standing in for the aggregation pages. */
    private static UserUsageCursor cursorOver(List<UserUsageCursor.UserUsage> entries) {
        Deque<UserUsageCursor.UserUsage> queue = new ArrayDeque<>(entries);
        return new UserUsageCursor() {
            @Override
            protected UserUsage fetchNext() {
                return queue.poll();
            }
        };
    }

    private static UserUsageCursor.UserUsage attributed(String user, Map<String, Long> buckets) {
        return attributed(user, buckets, "10.20.30.40");
    }

    private static UserUsageCursor.UserUsage attributed(String user, Map<String, Long> buckets, String nasIp) {
        long total = buckets.values().stream().mapToLong(Long::longValue).sum();
        return new UserUsageCursor.UserUsage(user, buckets, total, true, nasIp);
    }

    /** Usage the cursor reports for one user against one bucket, or null when it has no day. */
    private static Long usageFor(UserUsageCursor cursor, String user, String bucketId) {
        UserUsageCursor.UserUsage day = cursor.forUser(user);
        return day == null ? null : day.usageOn(bucketId);
    }

    @Test
    void returnsTheUsageOfTheRequestedBucket() {
        UserUsageCursor cursor = cursorOver(List.of(
                attributed("alice", Map.of("DATA_1", 100L, "NIGHT_1", 5L))));

        assertEquals(100L, usageFor(cursor, "alice", "DATA_1"));
    }

    @Test
    void carriesTheNasAddressTheDumpHasNoDatabaseColumnFor() {
        UserUsageCursor cursor = cursorOver(List.of(
                attributed("alice", Map.of("DATA_1", 100L), "10.20.30.40")));

        UserUsageCursor.UserUsage day = cursor.forUser("alice");

        assertEquals("10.20.30.40", day.nasIpAddress());
        assertEquals(100L, day.usageOn("DATA_1"), "one probe must serve both spliced columns");
    }

    @Test
    void aUserWhoseSessionsReportedNoNasAddressCarriesNone() {
        UserUsageCursor cursor = cursorOver(List.of(
                attributed("alice", Map.of("DATA_1", 100L), null)));

        assertNull(cursor.forUser("alice").nasIpAddress());
    }

    @Test
    void skipsPastUsersTheDumpNoLongerCaresAbout() {
        UserUsageCursor cursor = cursorOver(List.of(
                attributed("alice", Map.of("DATA_1", 1L)),
                attributed("bob", Map.of("DATA_1", 2L)),
                attributed("carol", Map.of("DATA_1", 3L))));

        // The dump walks users in the same order but has no row for bob.
        assertEquals(1L, usageFor(cursor, "alice", "DATA_1"));
        assertEquals(3L, usageFor(cursor, "carol", "DATA_1"));
    }

    @Test
    void usersWithNoSessionsThatDayReportNothing() {
        UserUsageCursor cursor = cursorOver(List.of(attributed("carol", Map.of("DATA_1", 3L))));

        assertNull(cursor.forUser("alice"), "alice has no usage document at all");
        assertEquals(3L, usageFor(cursor, "carol", "DATA_1"));
    }

    @Test
    void aUserWithUsageOnOtherBucketsOnlyReportsZeroForTheQuotaBucket() {
        UserUsageCursor cursor = cursorOver(List.of(attributed("alice", Map.of("VOICE_1", 900L))));

        assertEquals(0L, usageFor(cursor, "alice", "DATA_1"));
    }

    @Test
    void fallsBackToTheDailyTotalWhenTheBundleHasNoQuotaBucket() {
        UserUsageCursor cursor = cursorOver(List.of(attributed("alice", Map.of("DATA_1", 7L, "DATA_2", 3L))));

        assertEquals(10L, usageFor(cursor, "alice", null));
    }

    @Test
    void reportsTheDailyTotalWhenUsageCouldNotBeSplitPerBucket() {
        UserUsageCursor cursor = cursorOver(List.of(
                new UserUsageCursor.UserUsage("alice", Map.of(), 42L, false, "10.20.30.40")));

        assertEquals(42L, usageFor(cursor, "alice", "DATA_1"),
                "without a nested mapping the bucket split is not trustworthy, so the day is reported whole");
    }

    @Test
    void keepsReportingNothingOnceTheStreamRunsOut() {
        UserUsageCursor cursor = cursorOver(List.of(attributed("alice", Map.of("DATA_1", 1L))));

        assertEquals(1L, usageFor(cursor, "alice", "DATA_1"));
        assertNull(cursor.forUser("bob"));
        assertNull(cursor.forUser("carol"));
    }

    @Test
    void theEmptyCursorNeverMatches() {
        assertNull(UserUsageCursor.empty().forUser("alice"));
    }
}
