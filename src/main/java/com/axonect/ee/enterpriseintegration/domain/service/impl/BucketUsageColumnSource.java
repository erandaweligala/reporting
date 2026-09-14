package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.domain.client.UserUsageCursor;
import com.axonect.ee.enterpriseintegration.domain.client.UserUsageCursor.UserUsage;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtracts;
import com.axonect.ee.enterpriseintegration.domain.service.ExtractColumnSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.util.Objects;

/**
 * USAGE for the BUCKET_INSTANCE extract, read from the CDR session documents in Elasticsearch
 * rather than from the table's own counter.
 *
 * <p>It is the same figure the user data dump reports as UTLIZED_QUOTA, asked for the same way:
 * cdr-service records a usage delta per accounting event, tagged with the {@code bucketId} it was
 * drawn from and the {@code serviceId} of the bundle it was charged to, and keeps no running total
 * — so the total is summed over every daily index the cluster holds and looked up by the keys each
 * row already carries. The difference between the two reports is only which rows ask: the dump asks
 * once per user, for the one bucket behind QUOTA, while this asks once per bucket instance, which
 * is what the extract is a row of.
 *
 * <p><b>Which id the bucket is asked by.</b> Both of the ones the row holds. A session instance's
 * {@code bucketId} is BUCKET_INSTANCE.BUCKET_ID on one deployment and BUCKET_INSTANCE.ID on
 * another, and the two cannot be told apart by looking at a value — so the row offers the plan's
 * bucket first, which is the id the dump has to use, and its own id where the CDRs named that
 * instead. This is the one lookup the extract can do that the dump cannot: the dump reaches its
 * bucket through the plan and has no instance id to offer.
 *
 * <p><b>A subscriber whose CDRs carry no split at all.</b> Where {@code sessionInstances} is not
 * mapped as a nested type, Elasticsearch flattens the array and nothing can be summed per bucket:
 * the whole of what the subscriber has drawn is the only figure the cluster can give, and it is
 * exactly the figure the dump reports beside it as UTLIZED_QUOTA. This extract used to give the
 * column up to the table's own counter in that case — which is what left USAGE reading 0 while
 * UTLIZED_QUOTA read 10 for the same subscriber — so it reports that same total instead, and
 * reports it <em>once</em>: against the subscriber's leading quota bucket, which the statement's
 * ordering makes the live cycle's, and as 0 against every other bucket of theirs. That is the one
 * place a subscriber's whole usage may stand in for one bucket's, and it is sound only because
 * there is no split to contradict it — where the CDRs do attribute usage per bucket, every row
 * still gets its own figure and a bucket they never name is still a 0.
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
 * bucket none of their session instances name under either id, or holding one whose usage the CDRs
 * split by bundle and gave none of to this row's bundle. Neither can be told from the other in the
 * file, so both are counted and reported when the run ends.
 */
@Slf4j
public class BucketUsageColumnSource implements ExtractColumnSource {

    /** What a subscriber who was found, holding a bucket nothing is recorded against, reports. */
    private static final String NOTHING_RECORDED = "0";

    private final UserUsageCursor usage;
    private final boolean scopeToService;
    /** BUCKET_INSTANCE.BUCKET_TYPE that carries the data quota, as the dump reads it. */
    private final String quotaBucketType;

    /** Result set position of the joined username, which the CSV does not carry. */
    private final int userNameColumn;
    /** CSV positions this source reads and the one it writes. */
    private final int usageSlot;
    private final int serviceIdSlot;
    private final int bucketIdSlot;
    private final int bucketInstanceIdSlot;
    private final int bucketTypeSlot;

    /** The username whose figures are held, and the figures — one user's worth, never more. */
    private String userName;
    private UserUsage current;
    /**
     * Whether the held subscriber's unsplit total has already been written to one of their rows.
     * It is what keeps a figure that covers all of their buckets from being written against each
     * of them.
     */
    private boolean totalPlaced;

    private long rows;
    private long withoutLookupKey;
    private long withoutCdrUsage;
    private long withUnknownBucket;
    private long keyedByInstanceId;
    private long withBucketAndUsage;
    private long scopedToBundle;
    private long withoutSplit;
    private long tookSubscriberTotal;
    private long totalWithNoQuotaBucket;

    /**
     * @param spec            the bucket extract as it is being run, which is what says where the
     *                        columns sit; the positions are read off it rather than written down,
     *                        because a spliced column shifts every result set column after it
     * @param usage           cursor over the CDR totals, in the username order the statement orders
     *                        its rows by. Closed with this source, at the end of the run
     * @param scopeToService  whether a bucket's total is scoped to the bundle the row belongs to,
     *                        the same knob the dump reads it under
     * @param quotaBucketType BUCKET_INSTANCE.BUCKET_TYPE of the data quota bucket — the same
     *                        {@code report.user-dump.quota-bucket-type} the dump reports
     *                        UTLIZED_QUOTA for. It names the one row a subscriber's unsplit total
     *                        may be written against
     */
    public BucketUsageColumnSource(Spec spec, UserUsageCursor usage, boolean scopeToService,
                                   String quotaBucketType) {
        this.usage = usage;
        this.scopeToService = scopeToService;
        this.quotaBucketType = quotaBucketType;
        this.userNameColumn = spec.resultIndex(TableExtracts.USER_NAME_HELPER);
        this.usageSlot = spec.csvIndex(TableExtracts.USAGE_COLUMN);
        this.serviceIdSlot = spec.csvIndex(TableExtracts.SERVICE_ID_COLUMN);
        this.bucketIdSlot = spec.csvIndex(TableExtracts.BUCKET_ID_COLUMN);
        this.bucketInstanceIdSlot = spec.csvIndex(TableExtracts.BUCKET_INSTANCE_ID_COLUMN);
        this.bucketTypeSlot = spec.csvIndex(TableExtracts.BUCKET_TYPE_COLUMN);
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
            closeOutHeldUser();
            userName = user;
            current = usage.forUser(user);
            totalPlaced = false;
        }

        if (current == null) {
            row[usageSlot] = null;
            withoutCdrUsage++;
            return;
        }

        if (!current.attributable()) {
            // Nothing is recorded per bucket for this subscriber, so there is no lookup to do and
            // no id to do it by: what the cluster holds is the whole of what they have drawn, and
            // that is the figure the dump reports for them. It goes to the one bucket of theirs it
            // can be said of — see fillFromSubscriberTotal.
            fillFromSubscriberTotal(row);
            return;
        }

        String bucketId = row[bucketIdSlot];
        String bucketInstanceId = row[bucketInstanceIdSlot];
        if ((bucketId == null || bucketId.isEmpty())
                && (bucketInstanceId == null || bucketInstanceId.isEmpty())) {
            // Nothing to ask about under either id. The subscriber's total across their buckets is
            // what an unnamed bucket would otherwise be given, and that is not this row's usage —
            // the CDRs split it per bucket here, so a row that cannot say which bucket it is has
            // no share of it to claim.
            row[usageSlot] = null;
            withoutLookupKey++;
            return;
        }

        // The bucket id names the plan's bucket rather than one instance of it, so a recurring
        // subscriber draws on the same id cycle after cycle; the service instance is what makes
        // the total this row's bundle rather than every bundle's. Reading it at all is what
        // scope-to-service switches off.
        String serviceId = scopeToService ? row[serviceIdSlot] : null;

        row[usageSlot] = Long.toString(current.usageOn(serviceId, bucketId, bucketInstanceId));
        count(serviceId, bucketId, bucketInstanceId);
    }

    /**
     * Fills a row of a subscriber whose CDR usage carries no per-bucket split — the flattened
     * {@code sessionInstances} mapping, where the cluster can sum what a subscriber drew and
     * nothing finer.
     *
     * <p>The whole of it is what the dump reports as that subscriber's UTLIZED_QUOTA, so it is what
     * this reports too, against the bucket the dump reports it against: the quota bucket. The
     * statement orders a subscriber's rows live-cycle-first, so the first of them to reach here is
     * the current bucket rather than an expired one, and every row after it reports 0 — the figure
     * covers all of that subscriber's buckets, and writing it against each would read as their
     * usage several times over.
     *
     * <p>The bucket type is matched without regard to case. The dump's statement compares it in the
     * database, where {@code DATA} and {@code Data} are two different strings and a deployment
     * whose rows spell it the second way quietly matches neither bucket; here the comparison is
     * what decides where a figure lands rather than what it is, so it is the forgiving one.
     */
    private void fillFromSubscriberTotal(String[] row) {
        withoutSplit++;
        if (totalPlaced || !isQuotaBucket(row[bucketTypeSlot])) {
            row[usageSlot] = NOTHING_RECORDED;
            return;
        }
        totalPlaced = true;
        tookSubscriberTotal++;
        row[usageSlot] = Long.toString(current.total());
    }

    private boolean isQuotaBucket(String bucketType) {
        return quotaBucketType != null && !quotaBucketType.isEmpty()
                && quotaBucketType.equalsIgnoreCase(bucketType);
    }

    /**
     * Notes a held subscriber whose unsplit total never found a quota bucket to be written
     * against, which is the one way this extract can report less than the dump does: the dump
     * reports a subscriber's total whether or not they hold a quota bucket, and a row of this
     * extract is a bucket.
     */
    private void closeOutHeldUser() {
        if (current != null && !current.attributable() && !totalPlaced) {
            totalWithNoQuotaBucket++;
        }
    }

    /**
     * Tallies where the figure just written came from, for the lines logged when the run ends: no
     * CDR record for the subscriber at all, a bucket the CDRs never name under either id, which of
     * the two ids they did name it under, and — where there is both a bucket and usage — the row's
     * own bundle rather than one of the fallbacks. A few map probes per row rather than a record
     * per row: this runs once for every bucket instance in the database.
     */
    private void count(String serviceId, String bucketId, String bucketInstanceId) {
        if (!current.knowsBucket(bucketId, bucketInstanceId)) {
            withUnknownBucket++;
        } else if (!Objects.equals(current.bucketKey(bucketId, bucketInstanceId), bucketId)) {
            keyedByInstanceId++;
        }
        if (serviceId == null) {
            return;
        }
        withBucketAndUsage++;
        if (current.attributedTo(serviceId, bucketId, bucketInstanceId)) {
            scopedToBundle++;
        }
    }

    /**
     * Closes the aggregation cursor and reports what the column was filled from.
     *
     * <p>The lines answer questions the file cannot. A run where every row came out empty is not a
     * quiet subscriber base — it is a run whose usernames are not the ones the CDR documents are
     * keyed on, or whose scan covers days the sessions are not in. A run where every row holds a
     * bucket the CDRs never name is one whose session instances key usage on neither of the two ids
     * BUCKET_INSTANCE carries. A run where every row fell back to the bucket's total across bundles
     * is one whose CDRs do not carry SERVICE_INSTANCE.ID, and {@code scope-to-service: false} is
     * then the honest setting until they do. A run reporting subscriber totals is one whose
     * {@code sessionInstances} is not mapped as nested — the column is then as exact as
     * UTLIZED_QUOTA is and no more, and repairing the mapping is what makes it per-bucket again.
     */
    @Override
    public void close() throws Exception {
        try {
            closeOutHeldUser();
            if (rows == 0) {
                return;
            }
            log.info("BUCKET_INSTANCE took USAGE from the CDR session documents for {} row(s): {} "
                            + "found no CDR usage at all, whose USAGE is empty, {} hold a bucket "
                            + "their CDRs never name, whose USAGE is 0, and {} carry nothing to "
                            + "look up — a service that no longer resolves, or no bucket id — and "
                            + "are empty as well",
                    rows, withoutCdrUsage, withUnknownBucket, withoutLookupKey);
            if (keyedByInstanceId > 0) {
                log.info("BUCKET_INSTANCE read USAGE for {} row(s) under the bucket instance's "
                                + "own ID rather than under BUCKET_ID, which is the id this "
                                + "cluster's session instances key usage on",
                        keyedByInstanceId);
            }
            if (scopeToService && withBucketAndUsage > 0) {
                log.info("BUCKET_INSTANCE took USAGE from the row's own bundle for {} of {} row(s) "
                                + "with both a bucket and CDR usage; the rest drew nothing the "
                                + "CDRs attribute to that bundle",
                        scopedToBundle, withBucketAndUsage);
            }
            if (withoutSplit > 0) {
                log.info("BUCKET_INSTANCE found no per-bucket split for the subscribers behind {} "
                                + "row(s) — sessionInstances is not mapped as nested, so the whole "
                                + "of what a subscriber drew is all there is. It was reported "
                                + "against the {} quota bucket(s) it belongs to, the same figure "
                                + "USER_DATA_DUMP reports as UTLIZED_QUOTA, and as 0 against their "
                                + "other buckets; {} subscriber(s) held no {} bucket for it to be "
                                + "reported against at all",
                        withoutSplit, tookSubscriberTotal, totalWithNoQuotaBucket, quotaBucketType);
            }
        } finally {
            usage.close();
        }
    }
}
