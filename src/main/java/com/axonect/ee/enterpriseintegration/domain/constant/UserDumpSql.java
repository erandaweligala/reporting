package com.axonect.ee.enterpriseintegration.domain.constant;

import java.util.ArrayList;
import java.util.List;

/**
 * The single Oracle statement behind the USER_DATA_DUMP report.
 *
 * <p>Every column of the dump that lives in the database is produced by this one statement, so a
 * ~3 million row dump costs one cursor rather than one query per user. The three satellite tables
 * are folded in as pre-aggregated inline views, each of which Oracle can hash-join in a single
 * pass:
 *
 * <ul>
 *   <li>{@code svc} — the SERVICE_INSTANCE that was active on the reported day, one row per user,
 *       picked with ROW_NUMBER() instead of a correlated sub-select.</li>
 *   <li>{@code bkt} — BUCKET_INSTANCE collapsed to one row per service with the bandwidth and the
 *       quota bucket pivoted into columns by conditional aggregation. It joins {@code svc} so the
 *       optimizer prunes it to the shard's users instead of scanning the whole table.</li>
 *   <li>{@code mac} — AAA_USER_MAC_ADDRESS collapsed with LISTAGG, which is what produces the
 *       comma separated MAC lists the dump format expects.</li>
 * </ul>
 *
 * <p>Timestamps and numbers are converted to text by Oracle (TO_CHAR) rather than in Java. The
 * reader can then pull every column with {@code getString}, which keeps the row pipeline free of
 * Timestamp/BigDecimal allocations — at 3 million rows those allocations, not the I/O, are what
 * drives the collector.
 *
 * <p>The username range predicates are what let the dump be sharded: each shard scans a disjoint
 * slice of the username keyspace and the same slice is pushed into the Elasticsearch aggregation,
 * so both sides of the usage join stay aligned.
 */
public final class UserDumpSql {

    /** Column positions in the result set produced by {@link #build}. */
    public static final int COL_USER_ID = 1;
    /** Last column that maps straight onto a CSV column (BUNDLE_DEACTIVATION_DATE). */
    public static final int COL_BUNDLE_DEACTIVATION_DATE = 38;
    /** Helper column: the bucket whose usage becomes UTLIZED_QUOTA. Not written to the CSV. */
    public static final int COL_QUOTA_BUCKET_ID = 39;

    private UserDumpSql() {
    }

    /**
     * Builds the dump statement for one shard.
     *
     * @param usernameFrom    inclusive lower bound of the shard's username range, null for open
     * @param usernameTo      exclusive upper bound of the shard's username range, null for open
     * @param dayStart        start of the reported day (inclusive)
     * @param dayEnd          start of the following day (exclusive)
     * @param bandwidthBucket BUCKET_INSTANCE.BUCKET_TYPE holding the plan bandwidth
     * @param quotaBucket     BUCKET_INSTANCE.BUCKET_TYPE holding the data quota
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
           .append("        MAX(CASE WHEN b.BUCKET_TYPE = ? THEN b.BUCKET_ID END) AS PLAN_BANDWIDTH,")
           // An unlimited bucket has no balance to report, and the consumer reads an empty QUOTA
           // as "no cap" — so it is filtered out here rather than labelled.
           .append("        MAX(CASE WHEN b.BUCKET_TYPE = ? AND NVL(b.IS_UNLIMITED, 0) <> 1")
           .append("                 THEN TO_CHAR(b.INITIAL_BALANCE) END) AS QUOTA,")
           .append("        MAX(CASE WHEN b.BUCKET_TYPE = ? THEN b.BUCKET_ID END) AS QUOTA_BUCKET_ID")
           .append(" FROM BUCKET_INSTANCE b JOIN svc ON svc.ID = b.SERVICE_ID")
           .append(" GROUP BY b.SERVICE_ID")
           .append("), mac AS (")
           .append(" SELECT m.USER_NAME,")
           .append("        LISTAGG(m.MAC_ADDRESS, ',' ON OVERFLOW TRUNCATE '' WITHOUT COUNT)")
           .append("            WITHIN GROUP (ORDER BY m.ID) AS MAC_ADDRESSES,")
           .append("        LISTAGG(m.ORIGINAL_MAC_ADDRESS, ',' ON OVERFLOW TRUNCATE '' WITHOUT COUNT)")
           .append("            WITHIN GROUP (ORDER BY m.ID) AS ORIGINAL_MAC_ADDRESSES")
           .append(" FROM AAA_USER_MAC_ADDRESS m");
        params.add(bandwidthBucket);
        params.add(quotaBucket);
        params.add(quotaBucket);
        appendWhereRange(sql, params, "m.USER_NAME", usernameFrom, usernameTo);
        sql.append(" GROUP BY m.USER_NAME)")
           .append(" SELECT u.USER_NAME, u.GROUP_BANDWIDTH, u.BILLING, u.BILLING_ACCOUNT_REF, u.CIRCUIT_ID,")
           .append("        u.CONCURRENCY, u.CONTACT_EMAIL, u.CONTACT_NAME, u.CONTACT_NUMBER,")
           .append("        ").append(asText("u.CREATED_DATE", fmt)).append(",")
           .append("        u.CUSTOM_TIMEOUT, u.CYCLE_DATE, u.ENCRYPTION_METHOD, u.GROUP_ID, u.IDLE_TIMEOUT,")
           .append("        u.IP_ALLOCATION, u.IP_POOL_NAME, u.IPV4, u.IPV6, mac.MAC_ADDRESSES,")
           .append("        u.NAS_PORT_TYPE, mac.ORIGINAL_MAC_ADDRESSES, u.REMOTE_ID, u.REQUEST_ID,")
           .append("        u.SESSION_TIMEOUT, u.STATUS, u.SUBSCRIPTION,")
           .append("        ").append(asText("u.UPDATED_DATE", fmt)).append(",")
           .append("        u.SLMN, u.VLAN_ID, u.NAS_IP_ADDRESS, u.NOTIFICATION_TEMPLATES,")
           // CUSTOMER_ACTIVATION_DATE is the CSV column name, not a column on AAA_USER: the
           // activation timestamp the consumer expects there is CREATE_DATE.
           .append("        ").append(asText("u.CREATE_DATE", fmt)).append(",")
           .append("        ").append(asText("svc.SERVICE_START_DATE", fmt)).append(",")
           .append("        svc.PLAN_NAME, bkt.PLAN_BANDWIDTH, bkt.QUOTA,")
           .append("        ").append(asText("svc.EXPIRY_DATE", fmt)).append(",")
           .append("        bkt.QUOTA_BUCKET_ID")
           .append(" FROM AAA_USER u")
           .append(" LEFT JOIN svc ON svc.USERNAME = u.USER_NAME")
           .append(" LEFT JOIN bkt ON bkt.SERVICE_ID = svc.ID")
           .append(" LEFT JOIN mac ON mac.USER_NAME = u.USER_NAME");
        appendWhereRange(sql, params, "u.USER_NAME", usernameFrom, usernameTo);
        // Binary order, matching the order the Elasticsearch composite aggregation returns
        // usernames in, so the two streams can be merge-joined without buffering either side.
        sql.append(" ORDER BY u.USER_NAME");

        return new SqlStatement(sql.toString(), params);
    }

    /** Renders one timestamp column as text under the configured format model. */
    private static String asText(String column, String format) {
        return OracleText.timestampAsText(column, format);
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
