package com.axonect.ee.enterpriseintegration.domain.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** A user one of whose buckets the CDRs split by bundle in full, and that split. */
    private static UserUsageCursor.UserUsage split(String user, String bucketId, long total,
                                                   Map<String, Long> perServiceBucket) {
        return new UserUsageCursor.UserUsage(user, Map.of(bucketId, total), perServiceBucket,
                Set.of(bucketId), total, true, "10.20.30.40");
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
    void saysWhetherThatZeroCameFromABucketTheCdrHasNeverHeardOf() {
        // Both zeroes read the same in the file: one is a bucket the subscriber has not drawn on,
        // the other a bucket id the CDR does not key usage by at all. The shard log tells them
        // apart, so a whole column of zeroes can be diagnosed from a run rather than guessed at.
        UserUsageCursor.UserUsage alice = attributed("alice", Map.of("DATA_1", 0L, "VOICE_1", 900L));

        assertTrue(alice.knowsBucket("DATA_1"), "drawn on, and the nothing drawn from it is a fact");
        assertFalse(alice.knowsBucket("DATA_2"), "not a bucket any of alice's CDRs name");
    }

    @Test
    void aUserWhoseUsageCouldNotBeSplitPerBucketCountsAsKnowingEveryBucket() {
        // Without a nested mapping there is no split to look a bucket up in, and the figure such
        // a user gets is their whole total — never a zero that a missing bucket produced.
        UserUsageCursor.UserUsage alice =
                new UserUsageCursor.UserUsage("alice", Map.of(), Map.of(), 42L, false, null);

        assertTrue(alice.knowsBucket("DATA_1"));
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
    void aBundleMissingFromASplitThatNamesThemAllDrewNothing() {
        // The other side of the fallback above, and the reason the two are told apart. Where the
        // CDRs did name a bundle for every delta and the aggregation had room for all of them, a
        // bundle the split leaves out drew nothing — and reporting the bucket's total across
        // bundles instead is exactly what scope-to-service exists to prevent: every cycle's usage
        // reported against one cycle's bucket.
        UserUsageCursor.UserUsage alice = split("alice", "DATA_1", 1000L,
                Map.of(key("SVC-OLD", "DATA_1"), 900L, key("SVC-NOW", "DATA_1"), 100L));

        assertEquals(100L, alice.usageOn("SVC-NOW", "DATA_1"));
        assertEquals(0L, alice.usageOn("SVC-NEXT", "DATA_1"),
                "a cycle that has drawn nothing yet, not one that has drawn everything");
        assertEquals(1000L, alice.usageOn(null, "DATA_1"),
                "with the scope off the bucket across bundles is still what is reported");
    }

    @Test
    void aBucketCanBeAskedForByEitherOfTheTwoIdsTheDatabaseHoldsForIt() {
        // A session instance's bucketId is BUCKET_INSTANCE.BUCKET_ID on one deployment and
        // BUCKET_INSTANCE.ID on another. Only a caller holding both — the bucket extract, which is
        // a row of that table — can offer the second; the dump reaches its bucket through the plan
        // and passes the plan's id alone, which is why that one is tried first.
        UserUsageCursor.UserUsage alice = split("alice", "BI-77", 640L,
                Map.of(key("SVC-NOW", "BI-77"), 640L));

        assertEquals(640L, alice.usageOn("SVC-NOW", "DATA_1", "BI-77"));
        assertTrue(alice.knowsBucket("DATA_1", "BI-77"));
        assertEquals(0L, alice.usageOn("SVC-NOW", "DATA_1"),
                "asked by the plan's bucket alone it is a bucket the CDRs never name");
    }

    @Test
    void theBucketTheCdrsNamedUnderNeitherIdIsAZeroAndNotTheSubscribersWholeUsage() {
        UserUsageCursor.UserUsage alice = attributed("alice", Map.of("VOICE_1", 900L));

        assertEquals(0L, alice.usageOn("SVC-NOW", "DATA_1", "BI-77"));
        assertFalse(alice.knowsBucket("DATA_1", "BI-77"),
                "counted, so a file of zeroes can be diagnosed from the run that wrote it");
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
