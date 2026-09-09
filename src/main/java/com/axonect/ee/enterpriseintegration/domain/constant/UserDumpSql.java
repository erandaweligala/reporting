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
 *       comma separated MAC lists the dump format expects. The rows that would take a list past
 *       what LISTAGG can return are dropped before the aggregate sees them; see
 *       {@link #LISTAGG_MAX_BYTES}.</li>
 * </ul>
 *
 * <p>Timestamps and numbers are converted to text by Oracle (TO_CHAR) rather than in Java. The
 * reader can then pull every column with {@code getString}, which keeps the row pipeline free of
 * Timestamp/BigDecimal allocations — at 3 million rows those allocations, not the I/O, are what
 * drives the collector.
 *
 * <p>Not every column of the dump has a column of that name on AAA_USER. SLMN has none, so the
 * username is selected in its place — the consuming system keys on it, so it is bound rather than
 * left empty; CUSTOMER_ACTIVATION_DATE is likewise reported as CREATED_DATE. NAS_IP_ADDRESS has
 * none either, and no stand-in worth binding: it lives on the CDR session documents cdr-service
 * writes to Elasticsearch, so the result set carries nothing in that position and
 * {@code UserDataDumpReportDefinition} splices the value in from the same per-user stream that
 * already produces UTLIZED_QUOTA.
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
     * Bytes a LISTAGG result may reach before Oracle raises ORA-01489, and so the budget the MAC
     * lists are truncated to.
     *
     * <p>Oracle has a clause for exactly this — {@code ON OVERFLOW TRUNCATE} — but it only parses
     * on 12.2 and later. On an older server the parser reaches the {@code ON} where it expects the
     * closing bracket of the argument list and rejects the whole statement with ORA-00907 (missing
     * right parenthesis), which is a failed dump rather than a truncated MAC list. So the budget
     * is spent in the inline view instead: a running total of the bytes each row would contribute
     * decides which rows reach LISTAGG, leaving the aggregate itself in syntax every supported
     * Oracle understands. Do not reintroduce the overflow clause without knowing the server
     * version.
     *
     * <p>4000 is the limit for a VARCHAR2 under the default MAX_STRING_SIZE=STANDARD; an EXTENDED
     * database allows more, and truncating at the smaller of the two costs nothing here — a user
     * has a handful of MAC addresses, not two hundred.
     */
    private static final int LISTAGG_MAX_BYTES = 4000;

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
           .append("        LISTAGG(m.MAC_ADDRESS, ',')")
           .append("            WITHIN GROUP (ORDER BY m.ID) AS MAC_ADDRESSES,")
           .append("        LISTAGG(m.ORIGINAL_MAC_ADDRESS, ',')")
           .append("            WITHIN GROUP (ORDER BY m.ID) AS ORIGINAL_MAC_ADDRESSES")
           .append(" FROM (")
           .append("   SELECT ma.USER_NAME, ma.ID, ma.MAC_ADDRESS, ma.ORIGINAL_MAC_ADDRESS,")
           .append("          SUM(GREATEST(NVL(LENGTHB(ma.MAC_ADDRESS), 0),")
           .append("                       NVL(LENGTHB(ma.ORIGINAL_MAC_ADDRESS), 0)) + 1)")
           .append("              OVER (PARTITION BY ma.USER_NAME ORDER BY ma.ID")
           .append("                    ROWS UNBOUNDED PRECEDING) AS LIST_BYTES")
           .append("   FROM AAA_USER_MAC_ADDRESS ma");
        params.add(bandwidthBucket);
        params.add(quotaBucket);
        params.add(quotaBucket);
        appendWhereRange(sql, params, "ma.USER_NAME", usernameFrom, usernameTo);
        // Both lists are cut at the same row: the running total charges each row the wider of its
        // two addresses, so neither aggregate can overflow and the two columns stay row-aligned
        // with each other, which a per-column overflow clause would not guarantee.
        sql.append(" ) m WHERE m.LIST_BYTES <= ").append(LISTAGG_MAX_BYTES)
           .append(" GROUP BY m.USER_NAME)")
           .append(" SELECT u.USER_NAME, u.GROUP_BANDWIDTH, u.BILLING, u.BILLING_ACCOUNT_REF, u.CIRCUIT_ID,")
           .append("        u.CONCURRENCY, u.CONTACT_EMAIL, u.CONTACT_NAME, u.CONTACT_NUMBER,")
           .append("        ").append(asText("u.CREATED_DATE", fmt)).append(",")
           .append("        u.CUSTOM_TIMEOUT, u.CYCLE_DATE, u.ENCRYPTION_METHOD, u.GROUP_ID, u.IDLE_TIMEOUT,")
           .append("        u.IP_ALLOCATION, u.IP_POOL_NAME, u.IPV4, u.IPV6, mac.MAC_ADDRESSES,")
           .append("        u.NAS_PORT_TYPE, mac.ORIGINAL_MAC_ADDRESSES, u.REMOTE_ID, u.REQUEST_ID,")
           .append("        u.SESSION_TIMEOUT, u.STATUS, u.SUBSCRIPTION,")
           .append("        ").append(asText("u.UPDATED_DATE", fmt)).append(",")
           // AAA_USER carries no SLMN column, so the dump reports the username under it; and no
           // NAS_IP_ADDRESS either — that one is filled from the CDR session documents in
           // Elasticsearch, so nothing is selected for it here.
           .append("        u.USER_NAME AS SLMN, u.VLAN_ID, u.NOTIFICATION_TEMPLATES,")
           .append("        ").append(asText("u.CREATED_DATE", fmt)).append(",")
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
