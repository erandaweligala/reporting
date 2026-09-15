package com.axonect.ee.enterpriseintegration.domain.constant;

import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Column;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;

import java.util.List;

/**
 * The three table extracts, each defined by the header line the consuming system reads and the
 * column behind it.
 *
 * <p>The headers are the contract. They are taken from the sample extracts, not from the schema,
 * and they differ from it in ways that have to be preserved: the order is the sample's order and
 * not the DDL's, a column the sample omits stays omitted, and a column the sample renames keeps the
 * sample's name. {@code TableExtractsTest} asserts every header line so a well-meant tidy-up of
 * this file fails the build rather than the consumer's loader.
 */
public final class TableExtracts {

    public static final String MAC_SERVICE_TABLE_TYPE = "MAC_SERVICE_TABLE";
    public static final String PLAN_TO_BUCKET_TYPE = "PLAN_TO_BUCKET";
    public static final String BUCKET_INSTANCE_TYPE = "BUCKET_INSTANCE";

    /** The bucket extract's usage column, which is either the table's counter or the CDR total. */
    public static final String USAGE_COLUMN = "USAGE";
    /** The bucket's id, which is what the CDR session instances key usage on. */
    public static final String BUCKET_ID_COLUMN = "BUCKET_ID";
    /**
     * The bucket instance's own id — the second id a session instance's {@code bucketId} can be.
     *
     * <p>{@link #BUCKET_ID_COLUMN} names the <em>plan's</em> bucket, and it is the only one the
     * user data dump can ask by: the bucket it reports is reached through the plan. This extract is
     * a row of BUCKET_INSTANCE, so it holds both, and asking by both is what keeps its USAGE a real
     * figure on a deployment whose cdr-service keys usage on the row rather than on the plan —
     * where asking by the plan's bucket alone reports a column of zeros.
     */
    public static final String BUCKET_INSTANCE_ID_COLUMN = "ID";
    /** The SERVICE_INSTANCE the bucket belongs to, which is the bundle the usage is scoped to. */
    public static final String SERVICE_ID_COLUMN = "SERVICE_ID";
    /**
     * What kind of bucket the row is, which is what says whether it can hold data usage at all.
     *
     * <p>It is read for one case only: a subscriber whose CDR usage carries no per-bucket split,
     * where the whole of what they have drawn is the only figure there is and the quota bucket is
     * the one row of theirs it belongs against — the same bucket the user data dump reports that
     * figure against as UTLIZED_QUOTA. See {@code BucketUsageColumnSource}.
     */
    public static final String BUCKET_TYPE_COLUMN = "BUCKET_TYPE";
    /**
     * Helper column of {@link #bucketInstanceFromCdr}: the username that service is held by,
     * which is what the CDR documents are grouped under. It is read from the row and not written
     * to the file — BUCKET_INSTANCE carries no username column and must not start carrying one.
     */
    public static final String USER_NAME_HELPER = "USER_NAME";

    /**
     * The bucket extract's rule column, which reports the bandwidth bucket of the row's own bundle
     * — the figure the user data dump reports as PLAN_BANDWIDTH — rather than BUCKET_INSTANCE.RULE.
     */
    public static final String RULE_COLUMN = "RULE";

    /** The name the dump gives that figure, kept here so the two statements read alike. */
    private static final String PLAN_BANDWIDTH = "PLAN_BANDWIDTH";

    /**
     * The bandwidth bucket of each service, pre-aggregated and LEFT joined so that RULE can report
     * it. It is the dump's {@code bkt} inline view with the one column this extract needs:
     *
     * <ul>
     *   <li><b>The same definition, so the two files cannot disagree.</b> The dump reads
     *       PLAN_BANDWIDTH as {@code MAX(CASE WHEN BUCKET_TYPE = ? THEN BUCKET_ID END)} over a
     *       service's buckets; filtering the rows first and taking {@code MAX(BUCKET_ID)} of what
     *       is left is the same value, and the LEFT join is what makes a service with no bandwidth
     *       bucket empty here exactly as the CASE makes it empty there.</li>
     *   <li><b>Bound, and compared the way the dump compares it.</b> The bucket type is
     *       {@code report.user-dump.bandwidth-bucket-type}, bound rather than written into the SQL,
     *       and matched exactly — case included — because the dump matches it in the database. A
     *       forgiving comparison here would report a value in RULE for a deployment whose rows
     *       spell the type differently while PLAN_BANDWIDTH stayed empty beside it, which is the
     *       one thing these two columns must not do.</li>
     *   <li><b>Pre-aggregated rather than correlated.</b> One pass over the bandwidth buckets and a
     *       hash join, not a lookup per row: this runs once for every bucket instance in the
     *       database. It is the one aggregate in an extract, and it buys the whole column.</li>
     * </ul>
     */
    private static final String PLAN_BANDWIDTH_JOIN =
            " LEFT JOIN (SELECT SERVICE_ID, MAX(BUCKET_ID) AS " + PLAN_BANDWIDTH
                    + " FROM BUCKET_INSTANCE WHERE BUCKET_TYPE = ? GROUP BY SERVICE_ID) bw"
                    + " ON bw.SERVICE_ID = b.SERVICE_ID";

    /**
     * The service base: one row per SERVICE_INSTANCE.
     *
     * <p>Three columns are not a straight copy of the schema:
     *
     * <ul>
     *   <li>{@code SERVICE_ID} is SERVICE_INSTANCE.ID — the id BUCKET_INSTANCE.SERVICE_ID points
     *       at, which is what lets the consumer join this extract to the bucket extract.</li>
     *   <li>{@code RECURRING_FLAG} is a NUMBER(1) in the schema and Yes/No in the sample, so the
     *       mapping is done in SQL rather than left for the consumer to guess at.</li>
     *   <li>{@code GROUP_ID} is not a SERVICE_INSTANCE column at all. It is the subscriber's group
     *       on AAA_USER — the same column the user data dump reports under that name — which is
     *       why this extract has a join at all. SERVICE_INSTANCE.IS_GROUP is a flag, not an id,
     *       and is deliberately not what is reported here.</li>
     * </ul>
     *
     * <p>The join is a LEFT join so a service whose username no longer resolves to a user is still
     * extracted, with an empty GROUP_ID, rather than silently dropped from the base.
     */
    public static final Spec MAC_SERVICE_TABLE = new Spec(
            MAC_SERVICE_TABLE_TYPE,
            "SERVICE_INSTANCE si LEFT JOIN AAA_USER u ON u.USER_NAME = si.USERNAME",
            List.of(
                    Column.plain("SERVICE_ID", "si.ID"),
                    Column.plain("USER_NAME", "si.USERNAME"),
                    Column.plain("PLAN_ID", "si.PLAN_ID"),
                    Column.plain("PLAN_NAME", "si.PLAN_NAME"),
                    Column.plain("PLAN_TYPE", "si.PLAN_TYPE"),
                    Column.plain("RECURRING_FLAG", "DECODE(si.RECURRING_FLAG, 1, 'Yes', 'No')"),
                    Column.at("SERVICE_CYCLE_START_DATE", "si.CYCLE_START_DATE"),
                    Column.at("SERVICE_CYCLE_END_DATE", "si.CYCLE_END_DATE"),
                    Column.at("NEXT_CYCLE_START_DATE", "si.NEXT_CYCLE_START_DATE"),
                    Column.at("SERVICE_START_DATE", "si.SERVICE_START_DATE"),
                    Column.plain("STATUS", "si.STATUS"),
                    Column.at("CREATED_AT", "si.CREATED_AT"),
                    Column.at("EXPIRY_DATE", "si.EXPIRY_DATE"),
                    Column.at("UPDATED_AT", "si.UPDATED_AT"),
                    Column.plain("GROUP_ID", "u.GROUP_ID")));

    /**
     * The plan catalogue's bucket definitions: one row per PLAN_TO_BUCKET row, every column of the
     * table in the sample's order.
     *
     * <p>This is the small one — a row per plan per bucket, thousands rather than millions — and it
     * is written by exactly the same path as the other two. A second, "simple" path for it would be
     * a second thing to keep correct for no gain: one cursor and a streaming writer cost nothing on
     * a few thousand rows.
     */
    public static final Spec PLAN_TO_BUCKET = new Spec(
            PLAN_TO_BUCKET_TYPE,
            "PLAN_TO_BUCKET p",
            List.of(
                    Column.plain("ID", "p.ID"),
                    Column.plain("BUCKET_ID", "p.BUCKET_ID"),
                    Column.plain("CARRY_FORWARD", "p.CARRY_FORWARD"),
                    Column.plain("CARRY_FORWARD_VALIDITY", "p.CARRY_FORWARD_VALIDITY"),
                    Column.plain("CONSUMPTION_LIMIT", "p.CONSUMPTION_LIMIT"),
                    Column.plain("CONSUMPTION_LIMIT_WINDOW", "p.CONSUMPTION_LIMIT_WINDOW"),
                    Column.at("CREATED_AT", "p.CREATED_AT"),
                    Column.plain("INITIAL_QUOTA", "p.INITIAL_QUOTA"),
                    Column.plain("MAX_CARRY_FORWARD", "p.MAX_CARRY_FORWARD"),
                    Column.plain("PLAN_ID", "p.PLAN_ID"),
                    Column.plain("TOTAL_CARRY_FORWARD", "p.TOTAL_CARRY_FORWARD"),
                    Column.at("UPDATED_AT", "p.UPDATED_AT")));

    /**
     * The live buckets: one row per BUCKET_INSTANCE, which is one per bucket per service and so the
     * largest of the three extracts.
     *
     * <p>Two departures from the DDL, both from the sample: {@code USAGE} is reported before
     * {@code UPDATED_AT} rather than after it, and {@code IS_UNLIMITED} is not part of the extract
     * at all even though the table carries it. A third is not from the sample but from the
     * consuming system: {@code RULE} reports the bundle's bandwidth bucket rather than the table's
     * RULE column — see {@link #PLAN_BANDWIDTH_JOIN}.
     *
     * <p>This is the variant that reports the table's own USAGE column. What a deployment runs by
     * default is {@link #bucketInstanceFromCdr}, which reports the same figure the user data dump
     * reports as UTLIZED_QUOTA; this one is what it falls back to — see
     * {@code report.table-extract.usage-from-cdr}.
     *
     * @param bandwidthBucketType BUCKET_INSTANCE.BUCKET_TYPE holding the plan bandwidth, the same
     *                            {@code report.user-dump.bandwidth-bucket-type} the dump reads
     *                            PLAN_BANDWIDTH under
     */
    public static Spec bucketInstance(String bandwidthBucketType) {
        return new Spec(
                BUCKET_INSTANCE_TYPE,
                "BUCKET_INSTANCE b" + PLAN_BANDWIDTH_JOIN,
                bucketInstanceColumns(Column.plain(USAGE_COLUMN, "b.USAGE")),
                List.of(),
                null,
                bandwidthParams(bandwidthBucketType));
    }

    /**
     * The same extract with {@code USAGE} read from the CDR session documents in Elasticsearch
     * instead of from BUCKET_INSTANCE.USAGE — the figure the user data dump reports as
     * UTLIZED_QUOTA, for the bucket each row is.
     *
     * <p>Three things follow from where that figure lives, and all three are in this spec:
     *
     * <ul>
     *   <li><b>USAGE is spliced, not selected.</b> The statement reads nothing in that position,
     *       the way the dump statement reads nothing for NAS_IP_ADDRESS. Selecting the table's
     *       counter and overwriting it would leave a statement that says it reads a column the
     *       extract does not report.</li>
     *   <li><b>The username is joined in.</b> cdr-service groups its session documents by
     *       {@code userName}, so a bucket's usage is asked for by the username holding the service
     *       rather than by the bucket's own id. BUCKET_INSTANCE has no username, so
     *       SERVICE_INSTANCE is LEFT joined for it — left, because a bucket whose service no longer
     *       resolves is still a row of the table and still belongs in an extract of it. It is a
     *       {@link Spec#helpers() helper}: read from the row, never written to the file.</li>
     *   <li><b>The rows are ordered by it.</b> Elasticsearch hands its aggregation out in username
     *       order and the two sides are merge-joined, so neither has to be held in memory — which
     *       is the only reason an extract pays for an ORDER BY. NULLS LAST puts the buckets whose
     *       service did not resolve past the end of the merge rather than in front of it.</li>
     *   <li><b>A subscriber's own rows are ordered too.</b> The live bucket first: latest
     *       expiration, then highest id to break a tie. The merge needs only the username, but a
     *       subscriber whose CDR usage carries no per-bucket split has one figure to report and
     *       one row to report it against, and this is what makes that row the current cycle's
     *       bucket rather than whichever cycle the table happened to hand over first. The sort is
     *       already being paid for; two more keys inside each username are the rest of a sort that
     *       is happening anyway.</li>
     * </ul>
     */
    public static Spec bucketInstanceFromCdr(String bandwidthBucketType) {
        return new Spec(
                BUCKET_INSTANCE_TYPE,
                "BUCKET_INSTANCE b LEFT JOIN SERVICE_INSTANCE si ON si.ID = b.SERVICE_ID"
                        + PLAN_BANDWIDTH_JOIN,
                bucketInstanceColumns(Column.spliced(USAGE_COLUMN)),
                List.of(Column.plain(USER_NAME_HELPER, "si.USERNAME")),
                "si.USERNAME NULLS LAST, b.EXPIRATION DESC NULLS LAST, b.ID DESC",
                bandwidthParams(bandwidthBucketType));
    }

    /**
     * Every extract, in the order they are registered — with BUCKET_INSTANCE as it reads the
     * table's own USAGE column. Which of the two bucket specs a deployment actually runs is
     * {@code TableExtractReportsConfig}'s to decide; both carry the same header, which is the part
     * the consuming system holds anyone to.
     *
     * @param bandwidthBucketType the bucket type BUCKET_INSTANCE.RULE is reported from, as for
     *                            {@link #bucketInstance(String)}
     */
    public static List<Spec> all(String bandwidthBucketType) {
        return List.of(MAC_SERVICE_TABLE, PLAN_TO_BUCKET, bucketInstance(bandwidthBucketType));
    }

    /**
     * What the bandwidth join binds.
     *
     * <p>A setting that is absent binds an empty string rather than a null: it matches no bucket
     * type, so RULE comes out empty for every row — which is what the dump reports as
     * PLAN_BANDWIDTH under the same configuration — and the driver is never handed an untyped null
     * to guess a type for.
     */
    private static List<Object> bandwidthParams(String bandwidthBucketType) {
        return List.of(bandwidthBucketType == null ? "" : bandwidthBucketType);
    }

    /**
     * The bucket extract's columns, in the sample's order, around whichever {@code USAGE} column
     * the run reports. Shared by both specs so that the header — the contract — cannot drift
     * between them, and so that {@code RULE} is the bundle's bandwidth bucket in both: where USAGE
     * is read from is a deployment's business, and neither variant may write a different file.
     */
    private static List<Column> bucketInstanceColumns(Column usage) {
        return List.of(
                Column.plain("ID", "b.ID"),
                Column.plain(BUCKET_ID_COLUMN, "b.BUCKET_ID"),
                Column.plain("BUCKET_TYPE", "b.BUCKET_TYPE"),
                Column.plain("CARRY_FORWARD", "b.CARRY_FORWARD"),
                Column.plain("CARRY_FORWARD_VALIDITY", "b.CARRY_FORWARD_VALIDITY"),
                Column.plain("CONSUMPTION_LIMIT", "b.CONSUMPTION_LIMIT"),
                Column.plain("CONSUMPTION_LIMIT_WINDOW", "b.CONSUMPTION_LIMIT_WINDOW"),
                Column.plain("CURRENT_BALANCE", "b.CURRENT_BALANCE"),
                Column.at("EXPIRATION", "b.EXPIRATION"),
                Column.plain("INITIAL_BALANCE", "b.INITIAL_BALANCE"),
                Column.plain("MAX_CARRY_FORWARD", "b.MAX_CARRY_FORWARD"),
                Column.plain("PRIORITY", "b.PRIORITY"),
                Column.plain(RULE_COLUMN, "bw." + PLAN_BANDWIDTH),
                Column.plain(SERVICE_ID_COLUMN, "b.SERVICE_ID"),
                Column.plain("TIME_WINDOW", "b.TIME_WINDOW"),
                Column.plain("TOTAL_CARRY_FORWARD", "b.TOTAL_CARRY_FORWARD"),
                usage,
                Column.at("UPDATED_AT", "b.UPDATED_AT"));
    }

    private TableExtracts() {
    }
}
