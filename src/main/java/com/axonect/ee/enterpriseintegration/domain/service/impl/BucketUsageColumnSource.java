package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.domain.client.UserUsageCursor;
import com.axonect.ee.enterpriseintegration.domain.client.UserUsageCursor.UserUsage;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtracts;
import com.axonect.ee.enterpriseintegration.domain.service.ExtractColumnSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;

/**
 * USAGE for the BUCKET_INSTANCE extract, read from the CDR session documents in Elasticsearch
 * rather than from the table's own counter.
 *
 * <p>It is the same figure the user data dump reports as UTLIZED_QUOTA, asked for the same way:
 * cdr-service records a usage delta per accounting event, tagged with the {@code bucketId} it was
 * drawn from and the {@code serviceId} of the bundle it was charged to, and keeps no running total
 * — so the total is summed over every daily index the cluster holds and looked up by the pair each
 * row already carries, BUCKET_ID and SERVICE_ID. The difference between the two reports is only
 * which rows ask: the dump asks once per user, for the one bucket behind QUOTA, while this asks
 * once per bucket instance, which is what the extract is a row of.
 *
 * <p><b>Why the username.</b> The aggregation is grouped by {@code userName}, because that is what
 * a CDR document is keyed on, so the bucket the row describes is reached through the subscriber
 * holding its service rather than directly. The statement joins SERVICE_INSTANCE for that username
 * and orders by it; this walks the aggregation in the same order, so neither side is ever held in
 * memory — one user's figures at a time, whatever the row count. A user holds several buckets, so
 * the same username arrives on several consecutive rows and is probed once for the run of them:
 * {@link UserUsageCursor#forUser} hands a username out exactly once and cannot be asked again.
 *
 * <p><b>Reading an empty USAGE against a 0.</b> Deliberately the same distinction the dump
 * documents, because it is the same lookup. Empty is a row whose subscriber has no session document
 * anywhere in the scan — nothing was found to report. 0 is a subscriber who was found, holding a
 * bucket none of their session instances name: either nothing has been drawn from that bucket, or
 * the id the CDR keys usage on is not BUCKET_INSTANCE.BUCKET_ID. Neither can be told from the other
 * in the file, so both are counted and reported when the run ends.
 */
@Slf4j
public class BucketUsageColumnSource implements ExtractColumnSource {

    private final UserUsageCursor usage;
    private final boolean scopeToService;

    /** Result set position of the joined username, which the CSV does not carry. */
    private final int userNameColumn;
    /** CSV positions this source reads and the one it writes. */
    private final int usageSlot;
    private final int serviceIdSlot;
    private final int bucketIdSlot;

    /** The username whose figures are held, and the figures — one user's worth, never more. */
    private String userName;
    private UserUsage current;

    private long rows;
    private long withoutLookupKey;
    private long withoutCdrUsage;
    private long withUnknownBucket;
    private long withBucketAndUsage;
    private long scopedToBundle;

    /**
     * @param spec           the bucket extract as it is being run, which is what says where the
     *                       columns sit; the positions are read off it rather than written down,
     *                       because a spliced column shifts every result set column after it
     * @param usage          cursor over the CDR totals, in the username order the statement orders
     *                       its rows by. Closed with this source, at the end of the run
     * @param scopeToService whether a bucket's total is scoped to the bundle the row belongs to,
     *                       the same knob the dump reads it under
     */
    public BucketUsageColumnSource(Spec spec, UserUsageCursor usage, boolean scopeToService) {
        this.usage = usage;
        this.scopeToService = scopeToService;
        this.userNameColumn = spec.resultIndex(TableExtracts.USER_NAME_HELPER);
        this.usageSlot = spec.csvIndex(TableExtracts.USAGE_COLUMN);
        this.serviceIdSlot = spec.csvIndex(TableExtracts.SERVICE_ID_COLUMN);
        this.bucketIdSlot = spec.csvIndex(TableExtracts.BUCKET_ID_COLUMN);
    }

    @Override
    public void fill(ResultSet resultSet, String[] row) throws Exception {
        rows++;

        String user = resultSet.getString(userNameColumn);
        if (user == null) {
            // A bucket whose SERVICE_ID resolves to no SERVICE_INSTANCE. There is no username to
            // ask the CDR documents for, and the statement sorts these rows last precisely so that
            // reaching one cannot disturb the merge for the rows that do have one.
            row[usageSlot] = null;
            withoutLookupKey++;
            return;
        }

        if (!user.equals(userName)) {
            // One probe per username, not per row: a user's buckets arrive together, and the
            // cursor is forward-only — the second probe of a username would find it already past.
            // It is done before anything else this row might want, so the merge advances with the
            // username whatever the rest of the row turns out to hold.
            userName = user;
            current = usage.forUser(user);
        }

        if (current == null) {
            row[usageSlot] = null;
            withoutCdrUsage++;
            return;
        }

        String bucketId = row[bucketIdSlot];
        if (bucketId == null || bucketId.isEmpty()) {
            // Nothing to ask about. The subscriber's total across their buckets is what an
            // unnamed bucket would otherwise be given, and that is not this row's usage.
            row[usageSlot] = null;
            withoutLookupKey++;
            return;
        }

        // The bucket id names the plan's bucket rather than one instance of it, so a recurring
        // subscriber draws on the same id cycle after cycle; the service instance is what makes
        // the total this row's bundle rather than every bundle's. Reading it at all is what
        // scope-to-service switches off.
        String serviceId = scopeToService ? row[serviceIdSlot] : null;

        row[usageSlot] = Long.toString(current.usageOn(serviceId, bucketId));
        count(serviceId, bucketId);
    }

    /**
     * Tallies where the figure just written came from, for the lines logged when the run ends: no
     * CDR record for the subscriber at all, a bucket the CDRs never name, and — where there is
     * both — the row's own bundle rather than the bucket's total across bundles. Two map probes per
     * row rather than a record per row: this runs once for every bucket instance in the database.
     */
    private void count(String serviceId, String bucketId) {
        if (!current.knowsBucket(bucketId)) {
            withUnknownBucket++;
        }
        if (serviceId == null) {
            return;
        }
        withBucketAndUsage++;
        if (current.attributedTo(serviceId, bucketId)) {
            scopedToBundle++;
        }
    }

    /**
     * Closes the aggregation cursor and reports what the column was filled from.
     *
     * <p>Both lines answer questions the file cannot. A run where every row came out empty is not a
     * quiet subscriber base — it is a run whose usernames are not the ones the CDR documents are
     * keyed on, or whose scan covers days the sessions are not in. A run where every row fell back
     * to the bucket's total across bundles is one whose CDRs do not carry SERVICE_INSTANCE.ID, and
     * {@code scope-to-service: false} is then the honest setting until they do.
     */
    @Override
    public void close() throws Exception {
        try {
            if (rows == 0) {
                return;
            }
            log.info("BUCKET_INSTANCE took USAGE from the CDR session documents for {} row(s): {} "
                            + "found no CDR usage at all, whose USAGE is empty, {} hold a bucket "
                            + "their CDRs never name, whose USAGE is 0, and {} carry nothing to "
                            + "look up — a service that no longer resolves, or no bucket id — and "
                            + "are empty as well",
                    rows, withoutCdrUsage, withUnknownBucket, withoutLookupKey);
            if (scopeToService && withBucketAndUsage > 0) {
                log.info("BUCKET_INSTANCE took USAGE from the row's own bundle for {} of {} row(s) "
                                + "with both a bucket and CDR usage, and from the bucket's total "
                                + "across bundles for the rest",
                        scopedToBundle, withBucketAndUsage);
            }
        } finally {
            usage.close();
        }
    }
}
