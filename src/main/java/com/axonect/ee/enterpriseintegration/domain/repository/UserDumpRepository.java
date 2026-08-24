package com.axonect.ee.enterpriseintegration.domain.repository;

import com.axonect.ee.enterpriseintegration.domain.model.UserDumpChunk;
import com.axonect.ee.enterpriseintegration.domain.model.UserDumpColumns;
import com.axonect.ee.enterpriseintegration.domain.model.UserDumpRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the user dump from Oracle one keyset page at a time.
 *
 * <p>Two things about this query matter at three million rows:
 *
 * <p><b>Keyset, not offset.</b> Paging with {@code OFFSET n ROWS} makes Oracle produce and discard
 * the first {@code n} rows on every page, so a full pass costs O(rows&sup2;) — at this size the
 * later pages alone would dominate the whole report. Resuming from {@code USER_ID > :lastUserId}
 * instead lets each page start with a range scan straight into the primary key index, so every
 * page costs the same regardless of how far in it is.
 *
 * <p><b>Correlated lookups, not global joins.</b> The MAC / service / bucket lookups are
 * {@code OUTER APPLY} subqueries correlated to the page's users, so they touch only the child rows
 * of the few thousand users on that page. Written as plain joins to grouped or windowed inline
 * views, Oracle would aggregate the whole of each child table once per page — the same
 * O(rows&sup2;) trap in a different place. This depends on the child tables being indexed on their
 * lookup keys; see {@code docs/user-dump-report.md} for the required indexes.
 *
 * <p>The result is also streamed: the fetch size is set so the driver returns rows in blocks
 * rather than materialising a page-sized array of round trips, and nothing outside the current
 * page is ever held.
 *
 * <p>Requires Oracle 12.2 or later, for {@code OUTER APPLY} and for {@code LISTAGG ... ON OVERFLOW
 * TRUNCATE} — without the overflow clause one user with enough MAC addresses to pass 4000
 * characters would fail the aggregate and abort the entire report.
 */
@Repository
@Slf4j
public class UserDumpRepository {

    /**
     * Columns of the page of users, and the correlated lookups hung off it. The select list order
     * is the order {@link #mapRow} reads positionally.
     */
    private static final String SELECT_TEMPLATE = """
            SELECT
                u.USER_ID, u.USER_NAME, u.BANDWIDTH, u.BILLING, u.BILLING_ACCOUNT_REF,
                u.CIRCUIT_ID, u.CONCURRENCY, u.CONTACT_EMAIL, u.CONTACT_NAME, u.CONTACT_NUMBER,
                u.CREATED_DATE, u.CUSTOM_TIMEOUT, u.CYCLE_DATE, u.ENCRYPTION_METHOD, u.GROUP_ID,
                u.IDLE_TIMEOUT, u.IP_ALLOCATION, u.IP_POOL_NAME, u.IPV4, u.IPV6,
                u.MAC_ADDRESS, u.NAS_PORT_TYPE, u.REMOTE_ID, u.REQUEST_ID, u.SESSION_TIMEOUT,
                u.STATUS, u.SUBSCRIPTION, u.UPDATED_DATE, u.SLMN, u.VLAN_ID,
                u.NAS_IP_ADDRESS, u.TEMPLATE_ID, u.STATUS_CHANGED_DATE,
                m.ORIGINAL_MAC_ADDRESS,
                si.SERVICE_START_DATE, si.PLAN_NAME, si.EXPIRY_DATE,
                b.BUCKET_ID, b.IS_UNLIMITED, b.INITIAL_BALANCE
            FROM (
                SELECT
                    USER_ID, USER_NAME, BANDWIDTH, BILLING, BILLING_ACCOUNT_REF,
                    CIRCUIT_ID, CONCURRENCY, CONTACT_EMAIL, CONTACT_NAME, CONTACT_NUMBER,
                    CREATED_DATE, CUSTOM_TIMEOUT, CYCLE_DATE, ENCRYPTION_METHOD, GROUP_ID,
                    IDLE_TIMEOUT, IP_ALLOCATION, IP_POOL_NAME, IPV4, IPV6,
                    MAC_ADDRESS, NAS_PORT_TYPE, REMOTE_ID, REQUEST_ID, SESSION_TIMEOUT,
                    STATUS, SUBSCRIPTION, UPDATED_DATE, SLMN, VLAN_ID,
                    NAS_IP_ADDRESS, TEMPLATE_ID, STATUS_CHANGED_DATE
                FROM AAA_USER
                WHERE CREATED_DATE < ?
                %s
                ORDER BY USER_ID
                FETCH FIRST ? ROWS ONLY
            ) u
            OUTER APPLY (
                SELECT LISTAGG(x.ORIGINAL_MAC_ADDRESS, ',' ON OVERFLOW TRUNCATE WITHOUT COUNT)
                           WITHIN GROUP (ORDER BY x.ID) AS ORIGINAL_MAC_ADDRESS
                FROM AAA_USER_MAC_ADDRESS x
                WHERE x.USER_NAME = u.USER_NAME
            ) m
            OUTER APPLY (
                SELECT s.ID, s.SERVICE_START_DATE, s.PLAN_NAME, s.EXPIRY_DATE
                FROM (
                    SELECT s2.ID, s2.SERVICE_START_DATE, s2.PLAN_NAME, s2.EXPIRY_DATE
                    FROM SERVICE_INSTANCE s2
                    WHERE s2.USERNAME = u.USER_NAME
                      AND s2.SERVICE_START_DATE < ?
                      AND (s2.EXPIRY_DATE IS NULL OR s2.EXPIRY_DATE >= ?)
                    ORDER BY CASE WHEN s2.STATUS = 'ACTIVE' THEN 0 ELSE 1 END,
                             s2.SERVICE_START_DATE DESC,
                             s2.ID DESC
                ) s
                WHERE ROWNUM = 1
            ) si
            OUTER APPLY (
                SELECT bk.BUCKET_ID, bk.IS_UNLIMITED, bk.INITIAL_BALANCE
                FROM (
                    SELECT b2.BUCKET_ID, b2.IS_UNLIMITED, b2.INITIAL_BALANCE
                    FROM BUCKET_INSTANCE b2
                    WHERE b2.SERVICE_ID = si.ID
                    ORDER BY b2.PRIORITY, b2.ID
                ) bk
                WHERE ROWNUM = 1
            ) b
            ORDER BY u.USER_ID
            """;

    /**
     * Oracle treats the empty string as NULL, and {@code USER_ID > NULL} is never true, so the
     * first page cannot simply bind a low sentinel — it omits the predicate entirely.
     */
    private static final String FIRST_PAGE_SQL = SELECT_TEMPLATE.formatted("");
    private static final String NEXT_PAGE_SQL = SELECT_TEMPLATE.formatted("AND USER_ID > ?");

    // Select-list positions, 1-based as JDBC counts them.
    private static final int C_USER_ID = 1;
    private static final int C_USER_NAME = 2;
    private static final int C_BANDWIDTH = 3;
    private static final int C_BILLING = 4;
    private static final int C_BILLING_ACCOUNT_REF = 5;
    private static final int C_CIRCUIT_ID = 6;
    private static final int C_CONCURRENCY = 7;
    private static final int C_CONTACT_EMAIL = 8;
    private static final int C_CONTACT_NAME = 9;
    private static final int C_CONTACT_NUMBER = 10;
    private static final int C_CREATED_DATE = 11;
    private static final int C_CUSTOM_TIMEOUT = 12;
    private static final int C_CYCLE_DATE = 13;
    private static final int C_ENCRYPTION_METHOD = 14;
    private static final int C_GROUP_ID = 15;
    private static final int C_IDLE_TIMEOUT = 16;
    private static final int C_IP_ALLOCATION = 17;
    private static final int C_IP_POOL_NAME = 18;
    private static final int C_IPV4 = 19;
    private static final int C_IPV6 = 20;
    private static final int C_MAC_ADDRESS = 21;
    private static final int C_NAS_PORT_TYPE = 22;
    private static final int C_REMOTE_ID = 23;
    private static final int C_REQUEST_ID = 24;
    private static final int C_SESSION_TIMEOUT = 25;
    private static final int C_STATUS = 26;
    private static final int C_SUBSCRIPTION = 27;
    private static final int C_UPDATED_DATE = 28;
    private static final int C_SLMN = 29;
    private static final int C_VLAN_ID = 30;
    private static final int C_NAS_IP_ADDRESS = 31;
    private static final int C_TEMPLATE_ID = 32;
    private static final int C_STATUS_CHANGED_DATE = 33;
    private static final int C_ORIGINAL_MAC_ADDRESS = 34;
    private static final int C_SERVICE_START_DATE = 35;
    private static final int C_PLAN_NAME = 36;
    private static final int C_EXPIRY_DATE = 37;
    private static final int C_BUCKET_ID = 38;
    private static final int C_IS_UNLIMITED = 39;
    private static final int C_INITIAL_BALANCE = 40;

    private final JdbcTemplate jdbcTemplate;

    @Value("${report.user-dump.jdbc-fetch-size:2000}")
    private int jdbcFetchSize;

    @Value("${report.user-dump.timestamp-format:yyyy-MM-dd HH:mm:ss}")
    private String timestampFormat;

    @Value("${report.user-dump.unlimited-quota-label:Unlimited}")
    private String unlimitedQuotaLabel;

    public UserDumpRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Read the next page of users.
     *
     * @param lastUserId  exclusive lower bound on USER_ID, or null to start from the beginning
     * @param chunkSize   maximum rows to return
     * @param dayStart    inclusive start of the reported day
     * @param dayEnd      exclusive end of the reported day; users created at or after this are not
     *                    part of the day's snapshot, and neither are bundles started later
     */
    public UserDumpChunk fetchChunk(String lastUserId, int chunkSize,
                                    LocalDateTime dayStart, LocalDateTime dayEnd) {

        String sql = lastUserId == null ? FIRST_PAGE_SQL : NEXT_PAGE_SQL;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(timestampFormat);
        Timestamp startTs = Timestamp.valueOf(dayStart);
        Timestamp endTs = Timestamp.valueOf(dayEnd);

        List<UserDumpRow> rows = jdbcTemplate.query(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(sql,
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                    ps.setFetchSize(jdbcFetchSize);
                    int i = 1;
                    ps.setTimestamp(i++, endTs);
                    if (lastUserId != null) {
                        ps.setString(i++, lastUserId);
                    }
                    ps.setInt(i++, chunkSize);
                    // SERVICE_INSTANCE lookup: started within the day, not already expired before it
                    ps.setTimestamp(i++, endTs);
                    ps.setTimestamp(i, startTs);
                    return ps;
                },
                rs -> {
                    List<UserDumpRow> collected = new ArrayList<>(Math.min(chunkSize, 10_000));
                    while (rs.next()) {
                        collected.add(mapRow(rs, formatter));
                    }
                    return collected;
                });

        List<UserDumpRow> page = rows == null ? List.of() : rows;
        String cursor = page.isEmpty() ? null : page.get(page.size() - 1).values()[UserDumpColumns.USER_ID];
        return new UserDumpChunk(page, cursor);
    }

    private UserDumpRow mapRow(ResultSet rs, DateTimeFormatter formatter) throws SQLException {
        String[] v = new String[UserDumpColumns.COLUMN_COUNT];

        v[0] = rs.getString(C_USER_ID);
        v[1] = rs.getString(C_BANDWIDTH);
        v[2] = rs.getString(C_BILLING);
        v[3] = rs.getString(C_BILLING_ACCOUNT_REF);
        v[4] = rs.getString(C_CIRCUIT_ID);
        v[5] = rs.getString(C_CONCURRENCY);
        v[6] = rs.getString(C_CONTACT_EMAIL);
        v[7] = rs.getString(C_CONTACT_NAME);
        v[8] = rs.getString(C_CONTACT_NUMBER);
        v[9] = formatTimestamp(rs, C_CREATED_DATE, formatter);
        v[10] = rs.getString(C_CUSTOM_TIMEOUT);
        v[11] = rs.getString(C_CYCLE_DATE);
        v[12] = rs.getString(C_ENCRYPTION_METHOD);
        v[13] = rs.getString(C_GROUP_ID);
        v[14] = rs.getString(C_IDLE_TIMEOUT);
        v[15] = rs.getString(C_IP_ALLOCATION);
        v[16] = rs.getString(C_IP_POOL_NAME);
        v[17] = rs.getString(C_IPV4);
        v[18] = rs.getString(C_IPV6);
        v[19] = rs.getString(C_MAC_ADDRESS);
        v[20] = rs.getString(C_NAS_PORT_TYPE);
        v[21] = rs.getString(C_ORIGINAL_MAC_ADDRESS);
        v[22] = rs.getString(C_REMOTE_ID);
        v[23] = rs.getString(C_REQUEST_ID);
        v[24] = rs.getString(C_SESSION_TIMEOUT);
        v[25] = rs.getString(C_STATUS);
        v[26] = rs.getString(C_SUBSCRIPTION);
        v[27] = formatTimestamp(rs, C_UPDATED_DATE, formatter);
        v[28] = rs.getString(C_SLMN);
        v[29] = rs.getString(C_VLAN_ID);
        v[30] = rs.getString(C_NAS_IP_ADDRESS);
        v[31] = rs.getString(C_TEMPLATE_ID);
        v[32] = formatTimestamp(rs, C_STATUS_CHANGED_DATE, formatter);
        v[33] = formatTimestamp(rs, C_SERVICE_START_DATE, formatter);
        v[34] = rs.getString(C_PLAN_NAME);
        v[35] = rs.getString(C_BUCKET_ID);
        v[36] = quota(rs);
        // v[37] UTLIZED_QUOTA is filled in from Elasticsearch once the page is read.
        v[38] = formatTimestamp(rs, C_EXPIRY_DATE, formatter);

        return new UserDumpRow(v, rs.getString(C_USER_NAME), v[UserDumpColumns.PLAN_BANDWIDTH]);
    }

    /**
     * An unlimited bucket reports its quota as a label rather than a balance, matching how the
     * bundle is sold; everything else reports the balance it was provisioned with.
     */
    private String quota(ResultSet rs) throws SQLException {
        int isUnlimited = rs.getInt(C_IS_UNLIMITED);
        if (!rs.wasNull() && isUnlimited == 1) {
            return unlimitedQuotaLabel;
        }
        return rs.getString(C_INITIAL_BALANCE);
    }

    private String formatTimestamp(ResultSet rs, int columnIndex, DateTimeFormatter formatter)
            throws SQLException {
        Timestamp ts = rs.getTimestamp(columnIndex);
        return ts == null ? null : formatter.format(ts.toLocalDateTime());
    }
}
