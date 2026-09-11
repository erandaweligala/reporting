package com.axonect.ee.enterpriseintegration.domain.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        return new UserUsageCursor.UserUsage(user, buckets, Map.of(), total, true, nasIp);
    }

    /** A user whose bucket totals are also split by the bundle that drew each one. */
    private static UserUsageCursor.UserUsage perBundle(String user, Map<String, Long> buckets,
                                                       Map<String, Long> perServiceBucket) {
        long total = buckets.values().stream().mapToLong(Long::longValue).sum();
        return new UserUsageCursor.UserUsage(user, buckets, perServiceBucket, total, true, "10.20.30.40");
    }

    private static String key(String serviceId, String bucketId) {
        return UserUsageCursor.UserUsage.serviceBucketKey(serviceId, bucketId);
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
    void usersWithNoSessionsAtAllReportNothing() {
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
    void fallsBackToTheUsersWholeTotalWhenTheBundleHasNoQuotaBucket() {
        UserUsageCursor cursor = cursorOver(List.of(attributed("alice", Map.of("DATA_1", 7L, "DATA_2", 3L))));

        assertEquals(10L, usageFor(cursor, "alice", null));
    }

    @Test
    void reportsTheWholeTotalWhenUsageCouldNotBeSplitPerBucket() {
        UserUsageCursor cursor = cursorOver(List.of(
                new UserUsageCursor.UserUsage("alice", Map.of(), Map.of(), 42L, false, "10.20.30.40")));

        assertEquals(42L, usageFor(cursor, "alice", "DATA_1"),
                "without a nested mapping the bucket split is not trustworthy, so everything the "
                        + "user drew is reported whole");
    }

    @Test
    void reportsWhatTheNamedBundleDrewRatherThanWhatEveryBundleEverDrew() {
        // A bucket id names the plan's bucket, so a recurring subscriber draws on the same one
        // cycle after cycle: 900 of alice's 1000 belong to the bundle that has since expired.
        UserUsageCursor cursor = cursorOver(List.of(perBundle("alice",
                Map.of("DATA_1", 1000L),
                Map.of(key("SVC-OLD", "DATA_1"), 900L, key("SVC-NOW", "DATA_1"), 100L))));

        assertEquals(100L, cursor.forUser("alice").usageOn("SVC-NOW", "DATA_1"));
    }

    @Test
    void fallsBackToTheBucketTotalWhenNoBundleOfThatNameDrewOnIt() {
        // What a CDR whose serviceId is not SERVICE_INSTANCE.ID degrades to: the unscoped total,
        // which is a real figure, rather than a zero that would read as a subscriber using nothing.
        UserUsageCursor.UserUsage alice = perBundle("alice",
                Map.of("DATA_1", 1000L), Map.of(key("SVC-OLD", "DATA_1"), 1000L));
        UserUsageCursor cursor = cursorOver(List.of(alice));

        UserUsageCursor.UserUsage day = cursor.forUser("alice");
        assertEquals(1000L, day.usageOn("SVC-UNKNOWN", "DATA_1"));
        assertFalse(day.attributedTo("SVC-UNKNOWN", "DATA_1"),
                "the dump counts this per shard, so a wrong assumption shows up in the log");
        assertTrue(day.attributedTo("SVC-OLD", "DATA_1"));
    }

    @Test
    void withoutAServiceTheBucketsWholeTotalIsWhatIsReported() {
        UserUsageCursor cursor = cursorOver(List.of(perBundle("alice",
                Map.of("DATA_1", 1000L), Map.of(key("SVC-NOW", "DATA_1"), 100L))));

        assertEquals(1000L, cursor.forUser("alice").usageOn(null, "DATA_1"),
                "scope-to-service off asks with no service and gets the bucket across bundles");
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
