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
        long total = buckets.values().stream().mapToLong(Long::longValue).sum();
        return new UserUsageCursor.UserUsage(user, buckets, total, true);
    }

    @Test
    void returnsTheUsageOfTheRequestedBucket() {
        UserUsageCursor cursor = cursorOver(List.of(
                attributed("alice", Map.of("DATA_1", 100L, "NIGHT_1", 5L))));

        assertEquals(100L, cursor.usageFor("alice", "DATA_1"));
    }

    @Test
    void skipsPastUsersTheDumpNoLongerCaresAbout() {
        UserUsageCursor cursor = cursorOver(List.of(
                attributed("alice", Map.of("DATA_1", 1L)),
                attributed("bob", Map.of("DATA_1", 2L)),
                attributed("carol", Map.of("DATA_1", 3L))));

        // The dump walks users in the same order but has no row for bob.
        assertEquals(1L, cursor.usageFor("alice", "DATA_1"));
        assertEquals(3L, cursor.usageFor("carol", "DATA_1"));
    }

    @Test
    void usersWithNoSessionsThatDayReportNothing() {
        UserUsageCursor cursor = cursorOver(List.of(attributed("carol", Map.of("DATA_1", 3L))));

        assertNull(cursor.usageFor("alice", "DATA_1"), "alice has no usage document at all");
        assertEquals(3L, cursor.usageFor("carol", "DATA_1"));
    }

    @Test
    void aUserWithUsageOnOtherBucketsOnlyReportsZeroForTheQuotaBucket() {
        UserUsageCursor cursor = cursorOver(List.of(attributed("alice", Map.of("VOICE_1", 900L))));

        assertEquals(0L, cursor.usageFor("alice", "DATA_1"));
    }

    @Test
    void fallsBackToTheDailyTotalWhenTheBundleHasNoQuotaBucket() {
        UserUsageCursor cursor = cursorOver(List.of(attributed("alice", Map.of("DATA_1", 7L, "DATA_2", 3L))));

        assertEquals(10L, cursor.usageFor("alice", null));
    }

    @Test
    void reportsTheDailyTotalWhenUsageCouldNotBeSplitPerBucket() {
        UserUsageCursor cursor = cursorOver(List.of(
                new UserUsageCursor.UserUsage("alice", Map.of(), 42L, false)));

        assertEquals(42L, cursor.usageFor("alice", "DATA_1"),
                "without a nested mapping the bucket split is not trustworthy, so the day is reported whole");
    }

    @Test
    void keepsReportingNothingOnceTheStreamRunsOut() {
        UserUsageCursor cursor = cursorOver(List.of(attributed("alice", Map.of("DATA_1", 1L))));

        assertEquals(1L, cursor.usageFor("alice", "DATA_1"));
        assertNull(cursor.usageFor("bob", "DATA_1"));
        assertNull(cursor.usageFor("carol", "DATA_1"));
    }

    @Test
    void theEmptyCursorNeverMatches() {
        assertNull(UserUsageCursor.empty().usageFor("alice", "DATA_1"));
    }
}
