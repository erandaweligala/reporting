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
    private static final int INSTANCE_ID = SPEC.csvIndex(TableExtracts.ID_COLUMN);

    private static final String BUCKET = "SLT_DATA_100GB";

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
    void closesTheAggregationCursorWhenTheRunEnds() throws Exception {
        BucketUsageColumnSource source = source(cursorOf(), true);

        source.close();

        assertTrue(closed.get(), "the cursor is the extract's, and the extract has finished with it");
    }

    private BucketUsageColumnSource source(UserUsageCursor cursor, boolean scopeToService) {
        return new BucketUsageColumnSource(SPEC, cursor, scopeToService);
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

    private static UserUsage user(String userName, Map<String, Long> perBucket,
                                  Map<String, Long> perServiceBucket) {
        long total = perBucket.values().stream().mapToLong(Long::longValue).sum();
        return new UserUsage(userName, perBucket, perServiceBucket, total, true, null);
    }

    /** A row of the extract as the definition hands it over: every selected column already read. */
    private static String[] row(String serviceId, String bucketId) {
        return row(serviceId, bucketId, "4711");
    }

    private static String[] row(String serviceId, String bucketId, String instanceId) {
        String[] row = new String[SPEC.columns().size()];
        row[SERVICE_ID] = serviceId;
        row[BUCKET_ID] = bucketId;
        row[INSTANCE_ID] = instanceId;
        return row;
    }

    private static ResultSet resultSet(String userName) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(USER_NAME_COLUMN)).thenReturn(userName);
        return resultSet;
    }

    @Test
    void aCdrThatNamesTheBucketInstanceRatherThanThePlansBucketIsStillFound() throws Exception {
        // Which of the two ids cdr-service stamps on a session instance is not something this side
        // can settle, and asking for only BUCKET_ID cost the whole column: every row came out as
        // the 0 that means "a bucket the CDRs never drew on" while the documents held the usage.
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of("88001", 10L),
                        Map.of(UserUsage.serviceBucketKey("svc-1", "88001"), 10L))), true);

        String[] row = row("svc-1", BUCKET, "88001");
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("10", row[USAGE], "found under the bucket instance's own ID");
        assertEquals(BUCKET, row[BUCKET_ID], "nothing else in the row is touched");
    }

    @Test
    void thePlansBucketStillAnswersFirstSoAFigureThatIsAlreadyRightIsNeverDisturbed() throws Exception {
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of(BUCKET, 900L, "88001", 10L),
                        Map.of(UserUsage.serviceBucketKey("svc-1", BUCKET), 500L))), true);

        String[] row = row("svc-1", BUCKET, "88001");
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("500", row[USAGE], "the bundle's own share of the plan's bucket, as before");
    }

    @Test
    void aBucketNeitherIdNamesIsStillTheZeroThatSaysSo() throws Exception {
        BucketUsageColumnSource source = source(cursorOf(
                user("taiwowilliams", Map.of("SLT_NIGHT_50GB", 120L),
                        Map.of(UserUsage.serviceBucketKey("svc-1", "SLT_NIGHT_50GB"), 120L))), true);

        String[] row = row("svc-1", BUCKET, "88001");
        source.fill(resultSet("taiwowilliams"), row);

        assertEquals("0", row[USAGE]);
    }
}
