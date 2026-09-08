package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.config.UserDumpProperties;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.client.UsageAggregationClient;
import com.axonect.ee.enterpriseintegration.domain.client.UserUsageCursor;
import com.axonect.ee.enterpriseintegration.domain.constant.UserDumpSql;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.repository.UserDumpRowReader;
import com.axonect.ee.enterpriseintegration.domain.util.UserDumpShardPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDataDumpReportDefinitionTest {

    /**
     * The header the consuming system expects, byte for byte. Column order is part of the contract
     * with that system, and UTLIZED_QUOTA is spelled the way it spells it.
     */
    private static final String EXPECTED_HEADER =
            "USER_ID,GROUP_BANDWIDTH,BILLING,BILLING_ACCOUNT_REF,CIRCUIT_ID,CONCURRENCY,CONTACT_EMAIL,"
                    + "CONTACT_NAME,CONTACT_NUMBER,CREATED_DATE,CUSTOM_TIMEOUT,CYCLE_DATE,ENCRYPTION_METHOD,"
                    + "GROUP_ID,IDLE_TIMEOUT,IP_ALLOCATION,IP_POOL_NAME,IPV4,IPV6,MAC_ADDRESS,NAS_PORT_TYPE,"
                    + "ORIGINAL_MAC_ADDRESS,REMOTE_ID,REQUEST_ID,SESSION_TIMEOUT,STATUS,SUBSCRIPTION,"
                    + "UPDATED_DATE,SLMN,VLAN_ID,NAS_IP_ADDRESS,NOTIFICATION_TEMPLATES,"
                    + "CUSTOMER_ACTIVATION_DATE,BUNDLE_ACTIVATION_DATE,BUNDLE_NAME,PLAN_BANDWIDTH,QUOTA,"
                    + "UTLIZED_QUOTA,BUNDLE_DEACTIVATION_DATE";

    @TempDir
    Path tempDir;

    private UserDumpRowReader rowReader;
    private UsageAggregationClient usageAggregationClient;
    private UserDumpProperties properties;
    private UserDataDumpReportDefinition definition;

    @BeforeEach
    void setUp() {
        rowReader = mock(UserDumpRowReader.class);
        usageAggregationClient = mock(UsageAggregationClient.class);
        properties = new UserDumpProperties();
        properties.setShards(1);
        properties.setTimezone("UTC");
        definition = new UserDataDumpReportDefinition(
                rowReader, usageAggregationClient, properties, Runnable::run);
    }

    @Test
    void headerMatchesTheAgreedColumnOrderExactly() {
        String header = definition.columns().stream()
                .map(CsvColumn::getLabel)
                .collect(Collectors.joining(","));

        assertEquals(EXPECTED_HEADER, header);
        assertEquals(39, definition.columns().size());
    }

    @Test
    void isRegisteredUnderItsOwnReportType() {
        assertEquals("USER_DATA_DUMP", definition.reportType());
        assertEquals(UserDataDumpReportDefinition.REPORT_TYPE, definition.reportType());
    }

    @Test
    void pagedFetchingIsRefusedRatherThanQuietlyDegrading() {
        assertThrows(UnsupportedOperationException.class,
                () -> definition.fetchBatch(new DownloadReport(), 0, 100));
    }

    @Test
    void writesEachDatabaseColumnIntoItsAgreedPositionAndFillsUsageFromElasticsearch() throws Exception {
        String[] dbRow = databaseRow("taiwowilliams", "FTTH_50Mbps", "107374182400", "DATA_1");
        stubReader(List.<String[]>of(dbRow));
        stubUsage(Map.of("taiwowilliams", Map.of("DATA_1", 70093948746L)));

        Path output = outputWithHeader("dump.csv");
        long rows = definition.streamTo(report(), output);

        assertEquals(1, rows);
        List<String> lines = Files.readAllLines(output);
        assertEquals(EXPECTED_HEADER, lines.get(0));

        String[] written = lines.get(1).split(",", -1);
        assertEquals(39, written.length);
        assertEquals("taiwowilliams", written[0]);
        // Result set columns 1..37 land on CSV columns 1..37 untouched.
        assertEquals("col20", written[19], "MAC_ADDRESS");
        assertEquals("col22", written[21], "ORIGINAL_MAC_ADDRESS");
        assertEquals("FTTH_50Mbps", written[35], "PLAN_BANDWIDTH");
        assertEquals("107374182400", written[36], "QUOTA");
        // ...then usage is spliced in, and the last database column follows it.
        assertEquals("70093948746", written[37], "UTLIZED_QUOTA");
        assertEquals("bundle-end", written[38], "BUNDLE_DEACTIVATION_DATE");
    }

    @Test
    void anUnlimitedBundleReportsAnEmptyQuotaRatherThanALabel() throws Exception {
        // UserDumpSql leaves QUOTA null for an unlimited data bucket; it must reach the CSV as an
        // empty column, without disturbing the ones around it.
        stubReader(List.<String[]>of(databaseRow("unlimiteduser", "FTTH_50Mbps", null, "DATA_1")));
        stubUsage(Map.of("unlimiteduser", Map.of("DATA_1", 70093948746L)));

        Path output = outputWithHeader("unlimited.csv");
        definition.streamTo(report(), output);

        String[] written = Files.readAllLines(output).get(1).split(",", -1);
        assertEquals(39, written.length);
        assertEquals("FTTH_50Mbps", written[35], "PLAN_BANDWIDTH");
        assertEquals("", written[36], "QUOTA");
        assertEquals("70093948746", written[37], "UTLIZED_QUOTA");
    }

    @Test
    void usersWithoutUsageThatDayGetAnEmptyUtilizedQuota() throws Exception {
        stubReader(List.<String[]>of(databaseRow("quietuser", "FTTH_50Mbps", "1024", "DATA_1")));
        stubUsage(Map.of());

        Path output = outputWithHeader("quiet.csv");
        definition.streamTo(report(), output);

        String[] written = Files.readAllLines(output).get(1).split(",", -1);
        assertEquals("", written[37]);
        assertEquals("bundle-end", written[38], "the columns after it must not shift");
    }

    @Test
    void aggregatesUsageForTheDayBeforeTheRun() throws Exception {
        stubReader(List.of());
        stubUsage(Map.of());

        definition.streamTo(report(), outputWithHeader("day.csv"));

        ArgumentCaptor<LocalDate> day = ArgumentCaptor.forClass(LocalDate.class);
        verify(usageAggregationClient).open(day.capture(), eq(null), eq(null));
        assertEquals(LocalDate.now(ZoneId.of("UTC")).minusDays(1), day.getValue());
    }

    @Test
    void shardPartsAreConcatenatedInUsernameOrderRegardlessOfWhichShardFinishesFirst() throws Exception {
        properties.setShards(3);
        stubUsage(Map.of());

        // Each shard emits a single row naming the range it was given, so the finished file shows
        // whether the parts were stitched back together in keyspace order.
        when(rowReader.stream(any(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            UserDumpRowReader.RowHandler handler = invocation.getArgument(3);
            handler.handle(resultSetOf(databaseRow(shardInProgress.get(), "bw", "quota", "DATA_1")));
            return 1L;
        });

        Path output = outputWithHeader("sharded.csv");
        long rows = definition.streamTo(report(), output);

        assertEquals(3, rows);
        List<String> lines = Files.readAllLines(output);
        assertEquals(4, lines.size(), "header plus one row per shard");
        assertEquals(EXPECTED_HEADER, lines.get(0));
        assertEquals(expectedShardNames(3),
                lines.subList(1, 4).stream().map(line -> line.split(",", -1)[0]).toList());

        verify(rowReader, times(3)).stream(any(), anyInt(), anyInt(), any());
        assertTrue(listPartFiles().isEmpty(), "part files must be cleaned up: " + listPartFiles());
    }

    @Test
    void eachShardAggregatesOnlyItsOwnUsernameRange() throws Exception {
        properties.setShards(3);
        stubReader(List.of());
        stubUsage(Map.of());

        definition.streamTo(report(), outputWithHeader("ranges.csv"));

        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(usageAggregationClient, times(3)).open(any(), from.capture(), to.capture());

        List<UserDumpShardPlanner.UsernameRange> aggregated = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            aggregated.add(new UserDumpShardPlanner.UsernameRange(
                    from.getAllValues().get(i), to.getAllValues().get(i)));
        }
        aggregated.sort(Comparator.comparing(range -> range.fromInclusive() == null ? "" : range.fromInclusive()));

        assertEquals(UserDumpShardPlanner.plan(3), aggregated,
                "the ranges aggregated from Elasticsearch must be exactly the ranges scanned in Oracle");
    }

    @Test
    void aFailingShardLeavesNoPartFilesBehind() throws Exception {
        properties.setShards(3);
        stubUsage(Map.of());
        when(rowReader.stream(any(), anyInt(), anyInt(), any()))
                .thenThrow(new IllegalStateException("ORA-01555: snapshot too old"));

        Path output = outputWithHeader("failed.csv");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> definition.streamTo(report(), output));

        assertTrue(thrown.getMessage().contains("ORA-01555"), "the shard's own failure must surface");
        assertTrue(listPartFiles().isEmpty(), "part files must be cleaned up: " + listPartFiles());
    }

    /** Name a shard reports itself under: its lower bound, or a marker for the open-ended first. */
    private static final String OPEN_START = "start";

    private final java.util.concurrent.atomic.AtomicReference<String> shardInProgress =
            new java.util.concurrent.atomic.AtomicReference<>(OPEN_START);

    private static List<String> expectedShardNames(int shards) {
        return UserDumpShardPlanner.plan(shards).stream()
                .map(range -> range.fromInclusive() == null ? OPEN_START : range.fromInclusive())
                .toList();
    }

    private List<Path> listPartFiles() throws Exception {
        try (var files = Files.list(tempDir)) {
            return files.filter(path -> path.getFileName().toString().contains(".part")).toList();
        }
    }

    private DownloadReport report() {
        DownloadReport report = new DownloadReport();
        report.setId(7L);
        report.setReportType(UserDataDumpReportDefinition.REPORT_TYPE);
        report.setFormat("CSV");
        return report;
    }

    private Path outputWithHeader(String name) throws Exception {
        Path output = tempDir.resolve(name);
        Files.writeString(output, EXPECTED_HEADER + System.lineSeparator());
        return output;
    }

    private void stubReader(List<String[]> rows) throws Exception {
        when(rowReader.stream(any(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            UserDumpRowReader.RowHandler handler = invocation.getArgument(3);
            for (String[] row : rows) {
                handler.handle(resultSetOf(row));
            }
            return (long) rows.size();
        });
    }

    /** Serves usage from a fixed table, in the username order the merge-join expects. */
    private void stubUsage(Map<String, Map<String, Long>> usageByUser) {
        when(usageAggregationClient.open(any(), any(), any())).thenAnswer(invocation -> {
            String from = invocation.getArgument(1);
            shardInProgress.set(from == null ? OPEN_START : from);

            List<String> users = new ArrayList<>(usageByUser.keySet());
            users.sort(String::compareTo);
            var iterator = users.iterator();
            return new UserUsageCursor() {
                @Override
                protected UserUsage fetchNext() {
                    if (!iterator.hasNext()) {
                        return null;
                    }
                    String user = iterator.next();
                    Map<String, Long> buckets = usageByUser.get(user);
                    long total = buckets.values().stream().mapToLong(Long::longValue).sum();
                    return new UserUsage(user, buckets, total, true);
                }
            };
        });
    }

    /** A result set row: 37 directly mapped columns, the deactivation date, then the quota bucket. */
    private String[] databaseRow(String userName, String bandwidth, String quota, String quotaBucketId) {
        String[] values = new String[UserDumpSql.COL_QUOTA_BUCKET_ID];
        for (int i = 0; i < values.length; i++) {
            values[i] = "col" + (i + 1);
        }
        values[0] = userName;
        values[35] = bandwidth;
        values[36] = quota;
        values[UserDumpSql.COL_BUNDLE_DEACTIVATION_DATE - 1] = "bundle-end";
        values[UserDumpSql.COL_QUOTA_BUCKET_ID - 1] = quotaBucketId;
        return values;
    }

    private ResultSet resultSetOf(String[] values) throws Exception {
        Map<Integer, String> byColumn = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            byColumn.put(i + 1, values[i]);
        }
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(anyInt())).thenAnswer(
                invocation -> byColumn.get(invocation.<Integer>getArgument(0)));
        return resultSet;
    }
}
