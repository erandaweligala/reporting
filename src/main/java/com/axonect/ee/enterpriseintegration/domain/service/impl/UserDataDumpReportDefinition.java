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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * The full subscriber base extract: one CSV row per AAA user, describing the state of their
 * bundle on the previous day and how much of it they used.
 *
 * <p>Shape of the run, and why:
 *
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

    /** Zero-based position of UTLIZED_QUOTA, the one column that is not read from the database. */
    private static final int UTILIZED_QUOTA_POSITION = 37;

    /** Result set columns 1..37 land on CSV columns 1..37 unchanged. */
    private static final int LAST_DIRECT_COLUMN = 37;

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
        // One row buffer per shard, reused for every row. Each shard runs on its own thread and
        // never shares it.
        String[] row = new String[COLUMNS.size()];

        try (UserUsageCursor usage = usageAggregationClient.open(day, range.fromInclusive(), range.toExclusive());
             StreamingCsvWriter writer = new StreamingCsvWriter(target, properties.getCsvBufferBytes())) {

            long rows = rowReader.streamInBinaryOrder(
                    statement,
                    properties.getJdbcFetchSize(),
                    properties.getQueryTimeoutSeconds(),
                    resultSet -> writeRow(resultSet, row, usage, writer));

            log.info("Shard [{} .. {}) wrote {} rows in {} ms",
                    range.fromInclusive(), range.toExclusive(), rows, System.currentTimeMillis() - start);
            return rows;
        }
    }

    private void writeRow(ResultSet resultSet, String[] row, UserUsageCursor usage, StreamingCsvWriter writer)
            throws Exception {

        for (int column = UserDumpSql.COL_USER_ID; column <= LAST_DIRECT_COLUMN; column++) {
            row[column - 1] = resultSet.getString(column);
        }

        String quotaBucketId = resultSet.getString(UserDumpSql.COL_QUOTA_BUCKET_ID);
        Long utilized = usage.usageFor(row[0], quotaBucketId);

        row[UTILIZED_QUOTA_POSITION] = utilized == null ? null : Long.toString(utilized);
        row[UTILIZED_QUOTA_POSITION + 1] = resultSet.getString(UserDumpSql.COL_BUNDLE_DEACTIVATION_DATE);

        writer.writeRow(row);
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
