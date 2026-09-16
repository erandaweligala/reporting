package com.axonect.ee.enterpriseintegration.domain.constant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The single Oracle statement behind the USER_DATA_DUMP report.
 *
 * <p>Every column of the dump that lives in the database is produced by this one statement, so a
 * ~3 million row dump costs one cursor rather than one query per user. Two of the three satellite
 * tables are folded in as pre-aggregated inline views, each of which Oracle can hash-join in a
 * single pass:
 *
 * <ul>
 *   <li>{@code svc} — the SERVICE_INSTANCE that was active on the reported day, one row per user,
 *       picked with ROW_NUMBER() instead of a correlated sub-select.</li>
 *   <li>{@code bkt} — BUCKET_INSTANCE collapsed to one row per service with the bandwidth and the
 *       quota bucket pivoted into columns by conditional aggregation. It joins {@code svc} so the
 *       optimizer prunes it to the shard's users instead of scanning the whole table. What each
 *       pivot reports is the column the dump asks of that bucket: PLAN_BANDWIDTH is the bandwidth
 *       bucket's RULE — the rate the plan grants — and QUOTA the quota bucket's INITIAL_BALANCE.</li>
 * </ul>
 *
 * <p><b>Which bucket PLAN_BANDWIDTH is the RULE of.</b> The bandwidth bucket where the bundle has
 * one, and the bundle's own buckets otherwise. A pivot that names a bucket type no row carries is
 * not an error the run can see — it is a NULL, and it is what left PLAN_BANDWIDTH empty on every
 * row of a dump whose BUCKET_INSTANCE rows all held a RULE: a deployment that keeps one bucket per
 * bundle carries the rate rule on that bucket, whatever {@code bandwidth-bucket-type} is set to,
 * and one that spells the type {@code Bandwidth} never met the configured {@code BANDWIDTH} at all.
 * Both are answered here. The type comparison is made on normalised text ({@link #BUCKET_TYPE}),
 * so case and padding no longer decide whether a bucket is found, and the pivot falls back to the
 * RULE the service's buckets do carry — the bundle's own buckets and no one else's, since the join
 * to {@code svc} has already pruned the aggregate to this service instance. A deployment with a
 * real bandwidth bucket is unaffected: its pivot matches, and the fallback is never reached.
 *
 * <p>The MAC addresses are the one satellite table that is <em>not</em> folded into an aggregate.
 * AAA_USER_MAC_ADDRESS is joined row for row and the cursor therefore hands out one row per MAC
 * address a user holds, in {@code (username, id)} order; the comma separated lists the dump format
 * expects are joined up by {@code UserDataDumpReportDefinition} as it walks those rows. Collapsing
 * them here instead would mean LISTAGG, and LISTAGG is what the server this dump runs against
 * rejects — see {@link #build} — so the aggregate is spent where no Oracle version has an opinion
 * about it.
 *
 * <p>Timestamps and numbers are converted to text by Oracle (TO_CHAR) rather than in Java. The
 * reader can then pull every column with {@code getString}, which keeps the row pipeline free of
 * Timestamp/BigDecimal allocations — at 3 million rows those allocations, not the I/O, are what
 * drives the collector.
 *
 * <p>Not every column of the dump has a column of that name on AAA_USER. SLMN has none, so the
 * username is selected in its place — the consuming system keys on it, so it is bound rather than
 * left empty; NOTIFICATION_TEMPLATES is reported as TEMPLATE_ID, the column naming the templates
 * a user's notifications are sent from. NAS_IP_ADDRESS has none either, and no stand-in worth
 * binding: it lives on the CDR session documents cdr-service writes to Elasticsearch, so the
 * result set carries nothing in that position and {@code UserDataDumpReportDefinition} splices the
 * value in from the same per-user stream that already produces UTLIZED_QUOTA.
 *
 * <p>The statement ends with two columns that are not part of the dump at all — the quota bucket's
 * id and the id of the SERVICE_INSTANCE holding it. Neither is written to the CSV; they are what
 * the Elasticsearch side is asked with, so that UTLIZED_QUOTA is everything this bundle has drawn
 * from this bucket rather than everything every bundle ever drew from a bucket of that name.
 *
 * <p>Each stand-in is aliased to the dump column it fills, so the select list can be read against
 * the CSV header even though the reader indexes the result set by position. Selecting a column
 * AAA_USER does not have — which is what NOTIFICATION_TEMPLATES was — costs the whole dump rather
 * than the one column: Oracle rejects the statement with ORA-00904 (invalid identifier) on every
 * shard of every run, exactly the way a version-dependent construct does.
 *
 * <p>The username range predicates are what let the dump be sharded: each shard scans a disjoint
 * slice of the username keyspace and the same slice is pushed into the Elasticsearch aggregation,
 * so both sides of the usage join stay aligned.
 */
public final class UserDumpSql {

    /** Column positions in the result set produced by {@link #build}. */
    public static final int COL_USER_ID = 1;
    /** Last column before the NAS_IP_ADDRESS slot Elasticsearch fills (VLAN_ID). */
    public static final int COL_VLAN_ID = 30;
    /** First column after that slot (NOTIFICATION_TEMPLATES). */
    public static final int COL_NOTIFICATION_TEMPLATES = 31;
    /** Last column before the UTLIZED_QUOTA slot Elasticsearch fills (QUOTA). */
    public static final int COL_QUOTA = 36;
    /** Last column that maps straight onto a CSV column (BUNDLE_DEACTIVATION_DATE). */
    public static final int COL_BUNDLE_DEACTIVATION_DATE = 37;
    /** Helper column: the bucket whose usage becomes UTLIZED_QUOTA. Not written to the CSV. */
    public static final int COL_QUOTA_BUCKET_ID = 38;
    /**
     * Helper column: the SERVICE_INSTANCE that bucket belongs to, which is the bundle UTLIZED_QUOTA
     * is the usage of. Not written to the CSV.
     */
    public static final int COL_SERVICE_ID = 39;

    /**
     * How a bucket's type is compared with the configured one: on its normalised text, never on
     * the column as it stands.
     *
     * <p>BUCKET_TYPE carries whatever case and padding the plan was defined with — the CDR side of
     * this same pair of settings has matched it with {@code equalsIgnoreCase} for exactly that
     * reason — and an equality test against the configured {@code BANDWIDTH} misses a row holding
     * {@code Bandwidth} as completely as it misses a row holding nothing at all. A pivot that
     * matches no row is not an error anywhere: it is a NULL, and the column it fills reaches the
     * CSV empty on every row of the run. The bind is normalised the same way by
     * {@link #bucketType}, so both sides of the comparison are the one spelling.
     */
    private static final String BUCKET_TYPE = "UPPER(TRIM(b.BUCKET_TYPE))";

    private UserDumpSql() {
    }

    /**
     * Builds the dump statement for one shard.
     *
     * <p>Everything here parses on any Oracle from 9i onwards — subquery factoring, ROW_NUMBER,
     * conditional aggregation, ANSI outer joins, TO_CHAR. That is deliberate, and it is why the
     * MAC addresses are joined row for row instead of being collapsed with LISTAGG: this dump
     * failed every run with ORA-00907 (missing right parenthesis) while it used LISTAGG, first
     * with the 12.2-only {@code ON OVERFLOW TRUNCATE} clause and then, once that was removed,
     * with the plain {@code LISTAGG(...) WITHIN GROUP (...)} the removal left behind. A server
     * whose parser does not know LISTAGG reaches the WITHIN where it expects the subquery's
     * closing bracket and rejects the whole statement with exactly that error — the same failure
     * the overflow clause produced, which is why removing only the clause changed nothing.
     * Nothing left in this statement is newer than the parser that rejected it. Keep it that way:
     * a construct that needs a particular server version fails the entire dump, not one column.
     *
     * <p>The same error has a second, plainer cause, and this statement has earned it too: a column
     * alias written into the operand of a CAST — {@code CAST(u.CREATED_DATE as
     * CUSTOMER_ACTIVATION_DATE AS TIMESTAMP)} — puts a second AS where the cast's closing bracket
     * belongs, and Oracle rejects the statement before it looks at anything else. Aliases go on the
     * finished expression, never into the column: {@link #asText} takes the name as an argument for
     * exactly that reason, and {@link OracleText#validateColumn} refuses a column that carries one
     * rather than letting it reach the database.
     *
     * @param usernameFrom    inclusive lower bound of the shard's username range, null for open
     * @param usernameTo      exclusive upper bound of the shard's username range, null for open
     * @param dayStart        start of the reported day (inclusive)
     * @param dayEnd          start of the following day (exclusive)
     * @param bandwidthBucket BUCKET_INSTANCE.BUCKET_TYPE of the bucket whose RULE is the plan
     *                        bandwidth, matched without regard to case or padding. A bundle with
     *                        no bucket of that type reports the RULE its own buckets carry — see
     *                        the note on the class — so null or blank means "whichever bucket
     *                        holds the rule"
     * @param quotaBucket     BUCKET_INSTANCE.BUCKET_TYPE holding the data quota, matched the same
     *                        way
     * @param dateFormat      Oracle format model applied to every timestamp column
     */
    public static SqlStatement build(String usernameFrom,
                                     String usernameTo,
                                     java.sql.Timestamp dayStart,
                                     java.sql.Timestamp dayEnd,
                                     String bandwidthBucket,
                                     String quotaBucket,
                                     String dateFormat) {

        String fmt = OracleText.validateFormat(dateFormat);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(4096);

        sql.append("WITH svc AS (")
           .append(" SELECT s.USERNAME, s.ID, s.PLAN_NAME, s.SERVICE_START_DATE, s.EXPIRY_DATE")
           .append(" FROM (")
           .append("   SELECT si.USERNAME, si.ID, si.PLAN_NAME, si.SERVICE_START_DATE, si.EXPIRY_DATE,")
           .append("          ROW_NUMBER() OVER (PARTITION BY si.USERNAME")
           .append("                             ORDER BY si.SERVICE_START_DATE DESC NULLS LAST, si.ID DESC) AS RN")
           .append("   FROM SERVICE_INSTANCE si")
           .append("   WHERE (si.SERVICE_START_DATE IS NULL OR si.SERVICE_START_DATE < ?)")
           .append("     AND (si.EXPIRY_DATE IS NULL OR si.EXPIRY_DATE >= ?)");
        params.add(dayEnd);
        params.add(dayStart);
        appendRange(sql, params, "si.USERNAME", usernameFrom, usernameTo);
        sql.append(" ) s WHERE s.RN = 1")
           .append("), bkt AS (")
           .append(" SELECT b.SERVICE_ID,")
           // The bandwidth the plan grants is the bucket's RULE — the rate rule the bucket is
           // policed by — not its BUCKET_ID, which names the bucket the rule belongs to. The
           // bandwidth bucket is asked first; where the bundle has no bucket of that type, the
           // RULE its own buckets carry is the answer rather than an empty column. See the note
           // on the class.
           .append("        NVL(MAX(CASE WHEN ").append(BUCKET_TYPE).append(" = ? THEN b.RULE END),")
           .append("            MAX(b.RULE)) AS PLAN_BANDWIDTH,")
           // An unlimited bucket has no balance to report, and the consumer reads an empty QUOTA
           // as "no cap" — so it is filtered out here rather than labelled.
           .append("        MAX(CASE WHEN ").append(BUCKET_TYPE).append(" = ? AND NVL(b.IS_UNLIMITED, 0) <> 1")
           .append("                 THEN TO_CHAR(b.INITIAL_BALANCE) END) AS QUOTA,")
           .append("        MAX(CASE WHEN ").append(BUCKET_TYPE).append(" = ? THEN b.BUCKET_ID END) AS QUOTA_BUCKET_ID")
           .append(" FROM BUCKET_INSTANCE b JOIN svc ON svc.ID = b.SERVICE_ID")
           .append(" GROUP BY b.SERVICE_ID")
           .append(")");
        params.add(bucketType(bandwidthBucket));
        params.add(bucketType(quotaBucket));
        params.add(bucketType(quotaBucket));

        sql.append(" SELECT u.USER_NAME, u.BANDWIDTH AS GROUP_BANDWIDTH, u.BILLING, u.BILLING_ACCOUNT_REF, u.CIRCUIT_ID,")
           .append("        u.CONCURRENCY, u.CONTACT_EMAIL, u.CONTACT_NAME, u.CONTACT_NUMBER,")
           .append("        ").append(asText("u.CREATED_DATE", fmt, "CREATED_DATE")).append(",")
           .append("        u.CUSTOM_TIMEOUT, u.CYCLE_DATE, u.ENCRYPTION_METHOD, u.GROUP_ID, u.IDLE_TIMEOUT,")
           .append("        u.IP_ALLOCATION, u.IP_POOL_NAME, u.IPV4, u.IPV6, mac.MAC_ADDRESS,")
           .append("        u.NAS_PORT_TYPE, mac.ORIGINAL_MAC_ADDRESS, u.REMOTE_ID, u.REQUEST_ID,")
           .append("        u.SESSION_TIMEOUT, u.STATUS, u.SUBSCRIPTION,")
           .append("        ").append(asText("u.UPDATED_DATE", fmt, "UPDATED_DATE")).append(",")
           .append("        u.USER_NAME AS SLMN, u.VLAN_ID, u.TEMPLATE_ID AS NOTIFICATION_TEMPLATES,")
           .append("        ").append(asText("u.ACTIVATION_DATE", fmt, "CUSTOMER_ACTIVATION_DATE")).append(",")
           .append("        ").append(asText("svc.SERVICE_START_DATE", fmt, "BUNDLE_ACTIVATION_DATE")).append(",")
           .append("        svc.PLAN_NAME, bkt.PLAN_BANDWIDTH, bkt.QUOTA,")
           .append("        ").append(asText("svc.EXPIRY_DATE", fmt, "BUNDLE_DEACTIVATION_DATE")).append(",")
           // The last two are read by the dump rather than written to it: together they name the
           // bucket instance whose lifetime usage UTLIZED_QUOTA reports. The bucket id alone
           // would not — it names the plan's bucket, so a recurring bundle draws on the same one
           // cycle after cycle and the service id is what separates this cycle from the last.
           .append("        bkt.QUOTA_BUCKET_ID, svc.ID AS SERVICE_ID")
           .append(" FROM AAA_USER u")
           .append(" LEFT JOIN svc ON svc.USERNAME = u.USER_NAME")
           .append(" LEFT JOIN bkt ON bkt.SERVICE_ID = svc.ID")
           // One row per MAC address rather than a list: see the note on the class. The shard
           // bounds are repeated inside the join condition rather than added to the WHERE clause,
           // where they would turn the outer join into an inner one and drop every user who holds
           // no MAC address at all.
           .append(" LEFT JOIN AAA_USER_MAC_ADDRESS mac ON mac.USER_NAME = u.USER_NAME");
        appendRange(sql, params, "mac.USER_NAME", usernameFrom, usernameTo);
        appendWhereRange(sql, params, "u.USER_NAME", usernameFrom, usernameTo);
        // Binary order, matching the order the Elasticsearch composite aggregation returns
        // usernames in, so the two streams can be merge-joined without buffering either side. The
        // id breaks the tie between a user's own rows, which is the order their MAC addresses are
        // listed in.
        sql.append(" ORDER BY u.USER_NAME, mac.ID");

        return new SqlStatement(sql.toString(), params);
    }

    /**
     * Renders one timestamp column as text under the configured format model, named after the dump
     * column it fills.
     *
     * <p>The name is passed to {@link OracleText} rather than written into {@code column}: in the
     * column it would land inside the CAST and take the whole statement down with ORA-00907 — see
     * {@link OracleText#timestampAsText(String, String, String)}. Naming them is worth the care.
     * Five of these expressions have no name of their own, and the statement is read in the log
     * after the database has rejected it, where a TO_CHAR that says which dump column it fills is
     * the difference between reading the statement and counting the select list.
     */
    private static String asText(String column, String format, String alias) {
        return OracleText.timestampAsText(column, format, alias);
    }

    /**
     * The configured bucket type as it is compared: trimmed and upper-cased, to meet the column
     * {@link #BUCKET_TYPE} normalises the same way.
     *
     * <p>A type that is not configured at all becomes the empty string, which Oracle holds as NULL
     * and therefore compares equal to nothing — which is what an unset type should mean: a
     * deployment naming no bandwidth bucket reports whatever RULE the bundle's buckets carry (see
     * the NVL in {@link #build}), and one naming no quota bucket reports an empty QUOTA rather than
     * some arbitrary bucket's balance. It is the empty string rather than a Java null because the
     * reader binds every parameter with {@code setObject}, and a bare null there is a value the
     * driver has no type for — one more way for a missing setting to fail the whole dump instead
     * of one column.
     */
    private static String bucketType(String configured) {
        return configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
    }

    private static void appendWhereRange(StringBuilder sql, List<Object> params, String column,
                                         String from, String to) {
        if (from == null && to == null) {
            return;
        }
        sql.append(" WHERE 1 = 1");
        appendRange(sql, params, column, from, to);
    }

    private static void appendRange(StringBuilder sql, List<Object> params, String column,
                                    String from, String to) {
        if (from != null) {
            sql.append(" AND ").append(column).append(" >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND ").append(column).append(" < ?");
            params.add(to);
        }
    }

}
