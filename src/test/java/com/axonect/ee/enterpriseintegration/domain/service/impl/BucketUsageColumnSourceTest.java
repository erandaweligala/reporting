package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.domain.client.UserUsageCursor;
import com.axonect.ee.enterpriseintegration.domain.client.UserUsageCursor.UserUsage;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtracts;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * USAGE for the bucket extract, read the way UTLIZED_QUOTA is read: the total the CDR session
 * documents record against this bucket, for the bundle this row's service is.
 *
 * <p>The cursor here is the real merge-joining one, fed a list of users in the order Elasticsearch
 * would hand them out, so these also hold the ordering contract: rows arrive in username order and
 * a username handed out is never handed out again.
 */
class BucketUsageColumnSourceTest {

    private static final Spec SPEC = TableExtracts.BUCKET_INSTANCE_FROM_CDR;
    private static final int USER_NAME_COLUMN = SPEC.resultIndex(TableExtracts.USER_NAME_HELPER);
    private static final int USAGE = SPEC.csvIndex(TableExtracts.USAGE_COLUMN);
    private static final int SERVICE_ID = SPEC.csvIndex(TableExtracts.SERVICE_ID_COLUMN);
    private static final int BUCKET_ID = SPEC.csvIndex(TableExtracts.BUCKET_ID_COLUMN);
    private static final int BUCKET_INSTANCE_ID = SPEC.csvIndex(TableExtracts.BUCKET_INSTANCE_ID_COLUMN);
    private static final int BUCKET_TYPE = SPEC.csvIndex(TableExtracts.BUCKET_TYPE_COLUMN);

    /** BUCKET_INSTANCE.BUCKET_TYPE of the quota bucket, as report.user-dump reads it. */
    private static final String QUOTA_TYPE = "DATA";
    private static final String BUCKET = "SLT_DATA_100GB";
    /** BUCKET_INSTANCE.ID of the row: the other id a session instance's bucketId can be. */
    private static final String INSTANCE = "BI-77";

    private final AtomicBoolean closed = new AtomicBoolean();

    @Test
    void reportsTheShareOfTheBucketDrawnByTheBundleTheRowBelongsTo() throws Exception {
        // A bucket id names the plan's bucket, so a subscriber on a recurring plan draws on the
        // same id every cycle. The row is one cycle's bucket instance, and what belongs in it is
        // that cycle's usage — not the sum of every cycle's, which is what the bucket total is.
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of(BUCKET, 900L),
                        Map.of(UserUsage.serviceBucketKey("svc-1", BUCKET), 500L,
                                UserUsage.serviceBucketKey("svc-2", BUCKET), 400L))), true);

        String[] row = row("svc-2", BUCKET);
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("400", row[USAGE]);
        assertEquals("svc-2", row[SERVICE_ID], "nothing else in the row is touched");
        assertEquals(BUCKET, row[BUCKET_ID]);
    }

    @Test
    void everyBucketOfOneSubscriberIsFilledFromTheSingleProbeTheyGet() throws Exception {
        // A user holds several buckets, so the same username arrives on several consecutive rows.
        // The cursor hands a username out exactly once — a second probe would find the stream past
        // it and leave every bucket after the first empty.
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of(BUCKET, 900L, "SLT_NIGHT_50GB", 120L),
                        Map.of(UserUsage.serviceBucketKey("svc-1", BUCKET), 900L,
                                UserUsage.serviceBucketKey("svc-1", "SLT_NIGHT_50GB"), 120L))), true);

        String[] data = row("svc-1", BUCKET);
        String[] night = row("svc-1", "SLT_NIGHT_50GB");
        source.fill(resultSet("taiwowilliams"), data);
        source.fill(resultSet("taiwowilliams"), night);

        assertEquals("900", data[USAGE]);
        assertEquals("120", night[USAGE]);
    }

    @Test
    void anEmptyUsageIsASubscriberWithNoCdrAtAllAndAZeroIsABucketTheirCdrsNeverName() throws Exception {
        // The two look alike in a spreadsheet and mean different things — the same distinction the
        // dump documents for UTLIZED_QUOTA, because it is the same lookup.
        BucketUsageColumnSource source = source(
                cursorOf(user("taiwowilliams", Map.of("SLT_NIGHT_50GB", 120L),
                        Map.of(UserUsage.serviceBucketKey("svc-1", "SLT_NIGHT_50GB"), 120L))), true);

        String[] found = row("svc-1", BUCKET);
        String[] missing = row("svc-9", BUCKET);
        source.fill(resultSet("taiwowilliams"), found);
        source.fill(resultSet("zuhayrmohamed"), missing);

        assertEquals("0", found[USAGE], "found, holding a bucket the CDRs do not name");
        assertNull(missing[USAGE], "no session document anywhere in the scan, under that username");
    }

    @Test
    void aBucketWhoseServiceNoLongerResolvesIsStillARowOfTheExtract() throws Exception {
        // The extract is one row per row of BUCKET_INSTANCE, so the join to SERVICE_INSTANCE is a
        // LEFT one and these rows arrive with no username. They sort last, past every username the
        // merge is walking.
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of(BUCKET, 900L),
                        Map.of(UserUsage.serviceBucketKey("svc-1", BUCKET), 900L))), true);

        String[] orphan = row("svc-gone", BUCKET);
        source.fill(resultSet(null), orphan);

        assertNull(orphan[USAGE]);
        assertEquals("svc-gone", orphan[SERVICE_ID], "the row itself is written as it stands");
    }

    @Test
    void aRowNamingNoBucketIsNotGivenTheSubscribersWholeUsage() throws Exception {
        // Everything the subscriber has drawn is what an unnamed bucket would otherwise be
        // reported as, and that is not one bucket's usage. The next row of the same subscriber
        // still gets its own figure, so skipping this one does not skip them.
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of(BUCKET, 900L),
                        Map.of(UserUsage.serviceBucketKey("svc-1", BUCKET), 900L))), true);

        String[] unnamed = row("svc-1", null);
        String[] named = row("svc-1", BUCKET);
        source.fill(resultSet("taiwowilliams"), unnamed);
        source.fill(resultSet("taiwowilliams"), named);

        assertNull(unnamed[USAGE]);
        assertEquals("900", named[USAGE]);
    }

    @Test
    void withoutTheBundleScopeTheBucketsTotalAcrossEveryBundleIsReported() throws Exception {
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of(BUCKET, 900L),
                        Map.of(UserUsage.serviceBucketKey("svc-1", BUCKET), 500L,
                                UserUsage.serviceBucketKey("svc-2", BUCKET), 400L))), false);

        String[] row = row("svc-2", BUCKET);
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("900", row[USAGE], "scope-to-service off is the bucket's total across bundles");
    }

    @Test
    void aBundleTheCdrsSplitTheBucketByAndDoNotNameDrewNothing() throws Exception {
        // The split names every bundle that drew on the bucket, so a bundle missing from it drew
        // nothing and 0 is the figure. Reporting the bucket's total across bundles here is the very
        // thing scope-to-service exists to prevent — and on a report that is one row per bucket
        // instance it writes that same lifetime total onto every cycle's row for the bucket.
        BucketUsageColumnSource source = source(cursorOf(
                split("taiwowilliams", BUCKET, 900L,
                        Map.of(UserUsage.serviceBucketKey("svc-1", BUCKET), 500L,
                                UserUsage.serviceBucketKey("svc-2", BUCKET), 400L))), true);

        String[] drew = row("svc-1", BUCKET);
        String[] didNot = row("svc-3", BUCKET);
        source.fill(resultSet("taiwowilliams"), drew);
        source.fill(resultSet("taiwowilliams"), didNot);

        assertEquals("500", drew[USAGE]);
        assertEquals("0", didNot[USAGE], "this bundle drew none of the bucket, not all of it");
    }

    @Test
    void aSplitTheAggregationCouldNotReturnInFullStillFallsBackToTheBucketsTotal() throws Exception {
        // The deliberate degradation, and the reason the two are told apart: where the CDRs carry
        // no serviceId the database knows — or more bundles touched the bucket than
        // services-per-user has room for — nothing is known about who drew what, and an unscoped
        // total is a real figure where a zero would read as a subscriber who has used nothing.
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of(BUCKET, 900L), Map.of())), true);

        String[] row = row("svc-1", BUCKET);
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("900", row[USAGE]);
    }

    @Test
    void readsTheBucketByItsOwnIdWhereThatIsWhatTheCdrsNamed() throws Exception {
        // A session instance's bucketId is BUCKET_INSTANCE.BUCKET_ID on one deployment and
        // BUCKET_INSTANCE.ID on another. Asking by the plan's bucket alone is what leaves this
        // column a file of zeros while UTLIZED_QUOTA beside it looks right — the dump falls back to
        // the subscriber's whole total for a quota bucket it could not name, and this must not.
        BucketUsageColumnSource source = source(cursorOf(
                split("taiwowilliams", INSTANCE, 640L,
                        Map.of(UserUsage.serviceBucketKey("svc-1", INSTANCE), 640L))), true);

        String[] row = row("svc-1", BUCKET, INSTANCE);
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("640", row[USAGE]);
        assertEquals(BUCKET, row[BUCKET_ID], "the row is still written as it stands");
        assertEquals(INSTANCE, row[BUCKET_INSTANCE_ID]);
    }

    @Test
    void thePlansBucketIsWhatIsReportedWhenTheCdrsNameBothIds() throws Exception {
        // Only the id the CDRs actually keyed usage on should answer, and the plan's bucket — the
        // one the dump has to ask by — is tried first, so the two reports cannot drift apart on a
        // cluster where both ids happen to appear.
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of(BUCKET, 900L, INSTANCE, 5L), Map.of())), true);

        String[] row = row("svc-1", BUCKET, INSTANCE);
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("900", row[USAGE]);
    }

    @Test
    void aBucketNamedUnderNeitherIdIsStillAZeroRatherThanTheSubscribersWholeUsage() throws Exception {
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of("SLT_NIGHT_50GB", 120L), Map.of())), true);

        String[] row = row("svc-1", BUCKET, INSTANCE);
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("0", row[USAGE]);
    }

    @Test
    void aSubscriberWithNoPerBucketSplitReportsTheFigureTheDumpReportsAsUtlizedQuota() throws Exception {
        // sessionInstances is not mapped as nested, so Elasticsearch can sum what the subscriber
        // drew and nothing finer. That total is what the dump reports for them as UTLIZED_QUOTA,
        // so it is what the bucket behind that quota reports as USAGE — the two files are read
        // against each other, and this column falling back to the table's own counter here is what
        // left it at 0 beside a UTLIZED_QUOTA of 10 for the same subscriber.
        BucketUsageColumnSource source = source(cursorOf(unsplit("taiwowilliams", 10L)), true);

        String[] row = row("svc-1", BUCKET, INSTANCE);
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("10", row[USAGE]);
    }

    @Test
    void thatTotalIsReportedAgainstOneBucketRatherThanEachOfThem() throws Exception {
        // It covers every bucket the subscriber has, so writing it against each would read as
        // their usage several times over. The statement orders a subscriber's rows live cycle
        // first, so the one that takes it is the current bucket and the expired cycles report 0.
        BucketUsageColumnSource source = source(cursorOf(unsplit("taiwowilliams", 10L)), true);

        String[] live = row("svc-2", BUCKET, "BI-2");
        String[] lastCycle = row("svc-1", BUCKET, "BI-1");
        source.fill(resultSet("taiwowilliams"), live);
        source.fill(resultSet("taiwowilliams"), lastCycle);

        assertEquals("10", live[USAGE]);
        assertEquals("0", lastCycle[USAGE], "one figure, one row — not the same total twice");
    }

    @Test
    void aBucketThatHoldsNoQuotaIsNotTheOneThatTotalIsReportedAgainst() throws Exception {
        // The dump reports UTLIZED_QUOTA against the data quota bucket, so that is the bucket this
        // reports the same figure against. A bandwidth bucket draws no data usage and does not
        // take it, even when it is the row that arrives first.
        BucketUsageColumnSource source = source(cursorOf(unsplit("taiwowilliams", 10L)), true);

        String[] bandwidth = row("svc-1", "FTTH_50Mbps", "BI-1", "BANDWIDTH");
        String[] quota = row("svc-1", BUCKET, "BI-2");
        source.fill(resultSet("taiwowilliams"), bandwidth);
        source.fill(resultSet("taiwowilliams"), quota);

        assertEquals("0", bandwidth[USAGE]);
        assertEquals("10", quota[USAGE]);
    }

    @Test
    void theBucketTypeIsMatchedWhateverCaseTheDatabaseSpellsItIn() throws Exception {
        // BUCKET_TYPE is 'Data' on a deployment whose quota-bucket-type is configured as 'DATA'.
        // The comparison here decides where a figure is reported, not what it is, so it is the
        // forgiving one — a case that did not match would leave the figure nowhere.
        BucketUsageColumnSource source = source(cursorOf(unsplit("taiwowilliams", 10L)), true);

        String[] row = row("svc-1", BUCKET, INSTANCE, "Data");
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("10", row[USAGE]);
    }

    @Test
    void eachSubscriberGetsTheirOwnTotalOnceTheirOwnBucketIsReached() throws Exception {
        BucketUsageColumnSource source = source(cursorOf(
                unsplit("taiwowilliams", 10L), unsplit("zuhayrmohamed", 431L)), true);

        String[] first = row("svc-1", BUCKET, "BI-1");
        String[] second = row("svc-2", BUCKET, "BI-2");
        source.fill(resultSet("taiwowilliams"), first);
        source.fill(resultSet("zuhayrmohamed"), second);

        assertEquals("10", first[USAGE]);
        assertEquals("431", second[USAGE], "the next subscriber's total is their own");
    }

    @Test
    void aSplitTheCdrsDoCarryStillGivesEveryBucketItsOwnFigure() throws Exception {
        // The subscriber total stands in for one bucket's usage only where there is no split to
        // contradict it. Where there is one, a bucket the CDRs never name is still a 0 and not the
        // whole of what the subscriber drew.
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of("SLT_NIGHT_50GB", 120L), Map.of())), true);

        String[] row = row("svc-1", BUCKET, INSTANCE);
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("0", row[USAGE]);
    }

    @Test
    void closesTheAggregationCursorWhenTheRunEnds() throws Exception {
        BucketUsageColumnSource source = source(cursorOf(), true);

        source.close();

        assertTrue(closed.get(), "the cursor is the extract's, and the extract has finished with it");
    }

    private BucketUsageColumnSource source(UserUsageCursor cursor, boolean scopeToService) {
        return new BucketUsageColumnSource(SPEC, cursor, scopeToService, QUOTA_TYPE);
    }

    /** The aggregation as Elasticsearch hands it out: one user at a time, in username order. */
    private UserUsageCursor cursorOf(UserUsage... users) {
        Iterator<UserUsage> pending = List.of(users).iterator();
        return new UserUsageCursor() {
            @Override
            protected UserUsage fetchNext() {
                return pending.hasNext() ? pending.next() : null;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    /** A user whose splits claim no completeness, which is what a truncated aggregation hands back. */
    private static UserUsage user(String userName, Map<String, Long> perBucket,
                                  Map<String, Long> perServiceBucket) {
        long total = perBucket.values().stream().mapToLong(Long::longValue).sum();
        return new UserUsage(userName, perBucket, perServiceBucket, total, true, null);
    }

    /**
     * A user whose CDR usage carries no per-bucket split at all, which is what a flattened
     * sessionInstances mapping hands back: the whole of what they drew, and nothing finer.
     */
    private static UserUsage unsplit(String userName, long total) {
        return new UserUsage(userName, Map.of(), Map.of(), total, false, null);
    }

    /** A user one of whose buckets the CDRs split by bundle in full, and the split. */
    private static UserUsage split(String userName, String bucketId, long total,
                                   Map<String, Long> perServiceBucket) {
        return new UserUsage(userName, Map.of(bucketId, total), perServiceBucket,
                Set.of(bucketId), total, true, null);
    }

    /** A row of the extract as the definition hands it over: every selected column already read. */
    private static String[] row(String serviceId, String bucketId) {
        return row(serviceId, bucketId, null);
    }

    private static String[] row(String serviceId, String bucketId, String bucketInstanceId) {
        return row(serviceId, bucketId, bucketInstanceId, QUOTA_TYPE);
    }

    private static String[] row(String serviceId, String bucketId, String bucketInstanceId,
                                String bucketType) {
        String[] row = new String[SPEC.columns().size()];
        row[SERVICE_ID] = serviceId;
        row[BUCKET_ID] = bucketId;
        row[BUCKET_INSTANCE_ID] = bucketInstanceId;
        row[BUCKET_TYPE] = bucketType;
        return row;
    }

    private static ResultSet resultSet(String userName) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(USER_NAME_COLUMN)).thenReturn(userName);
        return resultSet;
    }
}
