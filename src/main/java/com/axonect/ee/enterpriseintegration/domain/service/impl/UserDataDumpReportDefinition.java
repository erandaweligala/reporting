package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.config.UserDumpProperties;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.client.UsageAggregationClient;
import com.axonect.ee.enterpriseintegration.domain.client.UserUsageCursor;
import com.axonect.ee.enterpriseintegration.domain.constant.SqlStatement;
import com.axonect.ee.enterpriseintegration.domain.constant.UserDumpSql;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.repository.StreamingRowReader;
import com.axonect.ee.enterpriseintegration.domain.service.StreamingReportDefinition;
import com.axonect.ee.enterpriseintegration.domain.util.StreamingCsvWriter;
import com.axonect.ee.enterpriseintegration.domain.util.UserDumpShardPlanner;
import com.axonect.ee.enterpriseintegration.domain.util.UserDumpShardPlanner.UsernameRange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * The full subscriber base extract: one CSV row per AAA user, describing the state of their
 * bundle on the previous day and how much of it they used.
 *
 * <p>Shape of the run, and why:
 *
 * <p>NAS_IP_ADDRESS has no AAA_USER column behind it, so the dump statement selects nothing for
 * it and the value is spliced in here from the CDR session documents cdr-service writes to
 * Elasticsearch — out of the same per-user stream that already carries UTLIZED_QUOTA, so it costs
 * no extra round trip. SLMN has no column either, but a stand-in worth binding: the dump
 * statement selects the username in its place.
 *
 * <p>UTLIZED_QUOTA is the total the reported bundle has drawn from its quota bucket, which is what
 * makes it readable next to QUOTA, the bucket's whole allowance. cdr-service keeps no such total:
 * it records a usage delta per accounting event, tagged with the bucket and the service instance
 * it was drawn against, so the total is summed over every CDR index up to the reported day and
 * asked for by the pair the dump statement carries in its last two columns.
 *
 * <p>The cursor hands out one row per MAC address rather than one row per user: collapsing the
 * addresses in SQL would mean LISTAGG, which is the construct the server rejects (see
 * {@link UserDumpSql#build}). So the rows are collapsed here instead, by
 * {@link MacCollapsingWriter} — the cursor is ordered by username, so a user's rows arrive
 * together and nothing is held but the one row being built.
 */
@Component
@Slf4j
public class UserDataDumpReportDefinition implements StreamingReportDefinition {

    public static final String REPORT_TYPE = "USER_DATA_DUMP";

    /**
     * The dump's columns, in the order the consuming system expects them. The order is part of the
     * contract and must not be rearranged. UTLIZED_QUOTA is spelled as the consumer spells it.
     */
    private static final List<CsvColumn> COLUMNS = List.of(
            new CsvColumn("user_id", "USER_ID"),
            new CsvColumn("group_bandwidth", "GROUP_BANDWIDTH"),
            new CsvColumn("billing", "BILLING"),
            new CsvColumn("billing_account_ref", "BILLING_ACCOUNT_REF"),
            new CsvColumn("circuit_id", "CIRCUIT_ID"),
            new CsvColumn("concurrency", "CONCURRENCY"),
            new CsvColumn("contact_email", "CONTACT_EMAIL"),
            new CsvColumn("contact_name", "CONTACT_NAME"),
            new CsvColumn("contact_number", "CONTACT_NUMBER"),
            new CsvColumn("created_date", "CREATED_DATE"),
            new CsvColumn("custom_timeout", "CUSTOM_TIMEOUT"),
            new CsvColumn("cycle_date", "CYCLE_DATE"),
            new CsvColumn("encryption_method", "ENCRYPTION_METHOD"),
            new CsvColumn("group_id", "GROUP_ID"),
            new CsvColumn("idle_timeout", "IDLE_TIMEOUT"),
            new CsvColumn("ip_allocation", "IP_ALLOCATION"),
            new CsvColumn("ip_pool_name", "IP_POOL_NAME"),
            new CsvColumn("ipv4", "IPV4"),
            new CsvColumn("ipv6", "IPV6"),
            new CsvColumn("mac_address", "MAC_ADDRESS"),
            new CsvColumn("nas_port_type", "NAS_PORT_TYPE"),
            new CsvColumn("original_mac_address", "ORIGINAL_MAC_ADDRESS"),
            new CsvColumn("remote_id", "REMOTE_ID"),
            new CsvColumn("request_id", "REQUEST_ID"),
            new CsvColumn("session_timeout", "SESSION_TIMEOUT"),
            new CsvColumn("status", "STATUS"),
            new CsvColumn("subscription", "SUBSCRIPTION"),
            new CsvColumn("updated_date", "UPDATED_DATE"),
            new CsvColumn("slmn", "SLMN"),
            new CsvColumn("vlan_id", "VLAN_ID"),
            new CsvColumn("nas_ip_address", "NAS_IP_ADDRESS"),
            new CsvColumn("notification_templates", "NOTIFICATION_TEMPLATES"),
            new CsvColumn("customer_activation_date", "CUSTOMER_ACTIVATION_DATE"),
            new CsvColumn("bundle_activation_date", "BUNDLE_ACTIVATION_DATE"),
            new CsvColumn("bundle_name", "BUNDLE_NAME"),
            new CsvColumn("plan_bandwidth", "PLAN_BANDWIDTH"),
            new CsvColumn("quota", "QUOTA"),
            new CsvColumn("utlized_quota", "UTLIZED_QUOTA"),
            new CsvColumn("bundle_deactivation_date", "BUNDLE_DEACTIVATION_DATE"));

    /**
     * Keys of the columns {@link UserDumpSql} renders with TO_CHAR, and so the columns a
     * spreadsheet has to be stopped from converting — see {@code excel-safe-timestamps} on
     * {@link UserDumpProperties}. They are named rather than numbered because the numbers move
     * with the column order and these are the only positions in this class that could move
     * silently: a mistake here writes a MAC address as a date, not a compile error.
     *
     * <p>CYCLE_DATE is not among them. It is selected as it stands, not rendered as a timestamp,
     * and the day-of-month it holds is not something a spreadsheet mistakes for a date.
     */
    private static final Set<String> TIMESTAMP_COLUMNS = Set.of(
            "created_date",
            "updated_date",
            "customer_activation_date",
            "bundle_activation_date",
            "bundle_deactivation_date");

    /**
     * Zero-based positions of the two columns that are not read from the database. AAA_USER
     * carries neither, so both are filled from the CDR session documents in Elasticsearch: the NAS
     * the user's sessions were anchored to on the reported day, and the total the bundle has drawn
     * from its quota bucket.
     */
    private static final int NAS_IP_ADDRESS_POSITION = 30;
    private static final int UTILIZED_QUOTA_POSITION = 37;

    /**
     * Zero-based positions of the two MAC columns. Both come straight from the result set, but one
     * result set row at a time rather than one CSV row at a time: the addresses are joined up here
     * rather than by the statement, so these are the positions the joined lists are written to.
     * They sit before NAS_IP_ADDRESS, so the result set column is one further along than the CSV
     * position, not one behind.
     */
    private static final int MAC_ADDRESS_POSITION = 19;
    private static final int ORIGINAL_MAC_ADDRESS_POSITION = 21;

    private final StreamingRowReader rowReader;
    private final UsageAggregationClient usageAggregationClient;
    private final UserDumpProperties properties;
    private final Executor shardExecutor;

    public UserDataDumpReportDefinition(StreamingRowReader rowReader,
                                        UsageAggregationClient usageAggregationClient,
                                        UserDumpProperties properties,
                                        @Qualifier("userDumpExecutor") Executor shardExecutor) {
        this.rowReader = rowReader;
        this.usageAggregationClient = usageAggregationClient;
        this.properties = properties;
        this.shardExecutor = shardExecutor;
    }

    @Override
    public String reportType() {
        return REPORT_TYPE;
    }

    @Override
    public List<CsvColumn> columns() {
        return COLUMNS;
    }

    @Override
    public long streamTo(DownloadReport report, Path outputPath) throws Exception {
        LocalDate day = reportedDay();
        List<UsernameRange> ranges = UserDumpShardPlanner.plan(properties.getShards());

        log.info("Report ID: {} | USER_DATA_DUMP for {} across {} shard(s)",
                report.getId(), day, ranges.size());

        if (ranges.size() == 1) {
            return runShard(ranges.get(0), day, outputPath);
        }

        // Shard 0 continues the output file itself — it already carries the header, and writing
        // into it directly saves copying the largest part back at the end.
        List<Path> parts = new ArrayList<>(ranges.size() - 1);
        List<CompletableFuture<Long>> pending = new ArrayList<>(ranges.size() - 1);

        try {
            for (int i = 1; i < ranges.size(); i++) {
                UsernameRange range = ranges.get(i);
                Path part = Files.createTempFile(outputPath.getParent(),
                        outputPath.getFileName() + ".part" + i + "-", ".csv");
                parts.add(part);
                pending.add(CompletableFuture.supplyAsync(
                        () -> runShardUnchecked(range, day, part), shardExecutor));
            }

            long rows = runShard(ranges.get(0), day, outputPath);
            for (CompletableFuture<Long> future : pending) {
                rows += future.join();
            }

            appendParts(outputPath, parts);
            return rows;

        } catch (CompletionException e) {
            throw unwrap(e);
        } finally {
            // Whatever went wrong, the other shards still hold their part files open; waiting for
            // them before deleting keeps a failed run from leaving orphaned files behind.
            awaitQuietly(pending);
            deleteQuietly(parts);
        }
    }

    /** Surfaces the shard's own failure rather than the wrapper the executor added. */
    private Exception unwrap(CompletionException e) {
        Throwable cause = e.getCause();
        return cause instanceof Exception actual ? actual : e;
    }

    private void awaitQuietly(List<CompletableFuture<Long>> pending) {
        for (CompletableFuture<Long> future : pending) {
            try {
                future.join();
            } catch (CompletionException | java.util.concurrent.CancellationException e) {
                log.debug("Shard finished exceptionally while unwinding the dump", e);
            }
        }
    }

    /** The day the dump reports on: D-1 by default, resolved in the CDR indices' own zone. */
    private LocalDate reportedDay() {
        return LocalDate.now(ZoneId.of(properties.getTimezone())).minusDays(properties.getDaysBack());
    }

    private long runShardUnchecked(UsernameRange range, LocalDate day, Path target) {
        try {
            return runShard(range, day, target);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Dumps one username range. The usage cursor and the JDBC cursor are opened together and
     * advanced in step, which is the whole point of the ordering both sides agree on.
     */
    private long runShard(UsernameRange range, LocalDate day, Path target) throws Exception {
        SqlStatement statement = UserDumpSql.build(
                range.fromInclusive(),
                range.toExclusive(),
                Timestamp.valueOf(day.atStartOfDay()),
                Timestamp.valueOf(day.plusDays(1).atStartOfDay()),
                properties.getBandwidthBucketType(),
                properties.getQuotaBucketType(),
                properties.getDateFormat());

        long start = System.currentTimeMillis();

        try (UserUsageCursor usage = usageAggregationClient.open(day, range.fromInclusive(), range.toExclusive());
             StreamingCsvWriter writer = new StreamingCsvWriter(
                     target, properties.getCsvBufferBytes(), timestampColumns())) {

            // One buffer per shard, reused for every user. Each shard runs on its own thread and
            // never shares it.
            MacCollapsingWriter rows = new MacCollapsingWriter(
                    usage, writer, properties.getUsage().isScopeToService());

            rowReader.streamInBinaryOrder(
                    statement,
                    properties.getJdbcFetchSize(),
                    properties.getQueryTimeoutSeconds(),
                    rows::accept);
            // The last user has no successor to close them out, so the stream ending does it.
            rows.flush();

            log.info("Shard [{} .. {}) wrote {} rows in {} ms",
                    range.fromInclusive(), range.toExclusive(), rows.written(), System.currentTimeMillis() - start);
            logQuotaAttribution(range, rows);
            return rows.written();
        }
    }

    /**
     * Reports how much of the shard's UTLIZED_QUOTA was the reported bundle's own usage.
     *
     * <p>The fallback — a bucket's total across every bundle that drew on it — is a plausible
     * looking number, so a wrong assumption about the CDR's serviceId cannot be seen in the file.
     * It can be seen here: a shard that attributed none of its rows is a shard whose session
     * instances are not keyed by SERVICE_INSTANCE.ID, and {@code scope-to-service: false} is then
     * the honest setting until they are.
     */
    private void logQuotaAttribution(UsernameRange range, MacCollapsingWriter rows) {
        if (!properties.getUsage().isScopeToService() || rows.withQuotaBucket() == 0) {
            return;
        }
        log.info("Shard [{} .. {}) took UTLIZED_QUOTA from the reported bundle's own usage for {} "
                        + "of {} user(s) with both a quota bucket and CDR usage, and from the "
                        + "bucket's total across bundles for the rest",
                range.fromInclusive(), range.toExclusive(),
                rows.scopedToBundle(), rows.withQuotaBucket());
    }

    /**
     * The flag per column the writer needs: true where {@link #TIMESTAMP_COLUMNS} names the
     * column, so the value goes to the file in a form a spreadsheet displays rather than converts.
     * Null when the dump is configured to write the bare text, which leaves the writer behaving
     * exactly as it did before the column was named at all.
     */
    private boolean[] timestampColumns() {
        if (!properties.isExcelSafeTimestamps()) {
            return null;
        }
        boolean[] flags = new boolean[COLUMNS.size()];
        for (int i = 0; i < COLUMNS.size(); i++) {
            flags[i] = TIMESTAMP_COLUMNS.contains(COLUMNS.get(i).getKey());
        }
        return flags;
    }

    /**
     * Turns the cursor's rows into CSV rows, joining up each user's MAC addresses on the way.
     *
     * <p>The statement joins AAA_USER_MAC_ADDRESS row for row, so a user with three MAC addresses
     * arrives as three otherwise identical rows, in {@code (username, id)} order. This holds the
     * row being built until the username changes, which is the whole of the state it keeps —
     * memory does not grow with the number of users, only with one user's MAC addresses.
     */
    private static final class MacCollapsingWriter {

        /**
         * Bytes a joined MAC list may reach before it is cut short.
         *
         * <p>The limit is the consuming system's, not ours: the lists used to be built by LISTAGG,
         * which returns a VARCHAR2 and raises ORA-01489 past 4 000 bytes, and the extract has been
         * bounded that way for as long as it has existed. Joining them here removes the database's
         * limit but not the format's, so the same budget is kept — and kept the same way, charging
         * each row the wider of its two addresses so both lists are cut at the same address and
         * stay row-aligned with each other. A user has a handful of MAC addresses, not two hundred.
         */
        private static final int MAC_LIST_MAX_BYTES = 4000;

        private final UserUsageCursor usage;
        private final StreamingCsvWriter writer;
        private final boolean scopeToService;
        private final String[] row = new String[COLUMNS.size()];
        private final StringBuilder macAddresses = new StringBuilder();
        private final StringBuilder originalMacAddresses = new StringBuilder();

        private String userName;
        private int macListBytes;
        private long written;
        private long withQuotaBucket;
        private long scopedToBundle;

        private MacCollapsingWriter(UserUsageCursor usage, StreamingCsvWriter writer,
                                    boolean scopeToService) {
            this.usage = usage;
            this.writer = writer;
            this.scopeToService = scopeToService;
        }

        void accept(ResultSet resultSet) throws Exception {
            String user = resultSet.getString(UserDumpSql.COL_USER_ID);
            if (!user.equals(userName)) {
                flush();
                startUser(resultSet, user);
            }
            appendMacAddresses(resultSet);
        }

        /** Writes the user being built, if there is one. */
        void flush() throws IOException {
            if (userName == null) {
                return;
            }
            row[MAC_ADDRESS_POSITION] = macAddresses.isEmpty() ? null : macAddresses.toString();
            row[ORIGINAL_MAC_ADDRESS_POSITION] =
                    originalMacAddresses.isEmpty() ? null : originalMacAddresses.toString();
            writer.writeRow(row);
            written++;
            userName = null;
        }

        long written() {
            return written;
        }

        /** Users written who have both a quota bucket and CDR usage to report against it. */
        long withQuotaBucket() {
            return withQuotaBucket;
        }

        /** How many of those had that usage found under the bundle the dump reports. */
        long scopedToBundle() {
            return scopedToBundle;
        }

        /**
         * Lays one result set row out over the CSV columns, splicing the two Elasticsearch-filled
         * columns into the positions the database has no column for. Everything before
         * NAS_IP_ADDRESS lands on its own position; everything between it and UTLIZED_QUOTA is one
         * place to the right of its result set column, which is why the second loop indexes the
         * row by the column number itself.
         */
        private void startUser(ResultSet resultSet, String user) throws Exception {
            for (int column = UserDumpSql.COL_USER_ID; column <= UserDumpSql.COL_VLAN_ID; column++) {
                row[column - 1] = resultSet.getString(column);
            }
            for (int column = UserDumpSql.COL_NOTIFICATION_TEMPLATES; column <= UserDumpSql.COL_QUOTA; column++) {
                row[column] = resultSet.getString(column);
            }

            String quotaBucketId = resultSet.getString(UserDumpSql.COL_QUOTA_BUCKET_ID);
            // The bucket's id names the plan's bucket, so the service instance holding it is what
            // says which of the user's bundles the usage should be the total of. Reading the column
            // at all is what scope-to-service switches off.
            String serviceId = scopeToService ? resultSet.getString(UserDumpSql.COL_SERVICE_ID) : null;
            // One probe per user: the cursor hands each username out once, and both spliced columns
            // come out of that single record. The user's remaining rows differ only in their MAC
            // address, so probing on the first of them is probing once.
            UserUsageCursor.UserUsage cdr = usage.forUser(user);

            row[NAS_IP_ADDRESS_POSITION] = cdr == null ? null : cdr.nasIpAddress();
            row[UTILIZED_QUOTA_POSITION] =
                    cdr == null ? null : Long.toString(cdr.usageOn(serviceId, quotaBucketId));
            row[UTILIZED_QUOTA_POSITION + 1] = resultSet.getString(UserDumpSql.COL_BUNDLE_DEACTIVATION_DATE);
            countAttribution(cdr, serviceId, quotaBucketId);

            userName = user;
            macAddresses.setLength(0);
            originalMacAddresses.setLength(0);
            macListBytes = 0;
        }

        /**
         * Tallies whether the figure just written was the reported bundle's own usage or the
         * bucket's total across bundles, for the one line the shard logs at the end. One extra map
         * probe per user rather than a record per user: this runs three million times.
         */
        private void countAttribution(UserUsageCursor.UserUsage cdr, String serviceId, String quotaBucketId) {
            if (cdr == null || serviceId == null || quotaBucketId == null || quotaBucketId.isEmpty()) {
                return;
            }
            withQuotaBucket++;
            if (cdr.attributedTo(serviceId, quotaBucketId)) {
                scopedToBundle++;
            }
        }

        /**
         * Adds this row's MAC address to each list, unless doing so would take either past the
         * budget. A user with no MAC address at all still produces one row, with both columns null.
         */
        private void appendMacAddresses(ResultSet resultSet) throws Exception {
            String mac = resultSet.getString(MAC_ADDRESS_POSITION + 1);
            String original = resultSet.getString(ORIGINAL_MAC_ADDRESS_POSITION + 1);
            if (mac == null && original == null) {
                return;
            }

            macListBytes += Math.max(utf8Length(mac), utf8Length(original)) + 1;
            if (macListBytes > MAC_LIST_MAX_BYTES) {
                return;
            }
            append(macAddresses, mac);
            append(originalMacAddresses, original);
        }

        private void append(StringBuilder list, String value) {
            if (value == null) {
                return;
            }
            if (!list.isEmpty()) {
                list.append(',');
            }
            list.append(value);
        }
    }

    /**
     * Bytes {@code value} occupies as UTF-8, counted rather than encoded: the budget is charged on
     * every row of a three million user dump, and {@code getBytes} would allocate an array to
     * measure a MAC address.
     */
    private static int utf8Length(String value) {
        if (value == null) {
            return 0;
        }
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * Appends the shard parts to the output file in shard order, so the finished dump reads in
     * username order. The copy happens inside the file system rather than through the heap.
     */
    private void appendParts(Path outputPath, List<Path> parts) throws IOException {
        try (FileChannel out = FileChannel.open(outputPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            for (Path part : parts) {
                try (FileChannel in = FileChannel.open(part, StandardOpenOption.READ)) {
                    long size = in.size();
                    long copied = 0;
                    while (copied < size) {
                        copied += in.transferTo(copied, size - copied, out);
                    }
                }
            }
        }
    }

    private void deleteQuietly(List<Path> parts) {
        for (Path part : parts) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException e) {
                log.warn("Could not delete dump part file {}", part, e);
            }
        }
    }
}
