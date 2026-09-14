package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.config.UserDumpProperties;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.client.NasAddressAggregationClient;
import com.axonect.ee.enterpriseintegration.domain.client.UserNasAddressCursor;
import com.axonect.ee.enterpriseintegration.domain.constant.SqlStatement;
import com.axonect.ee.enterpriseintegration.domain.constant.UserDumpSql;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.repository.StreamingRowReader;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private StreamingRowReader rowReader;
    private NasAddressAggregationClient nasAddressAggregationClient;
    private UserDumpProperties properties;
    private UserDataDumpReportDefinition definition;

    @BeforeEach
    void setUp() {
        rowReader = mock(StreamingRowReader.class);
        nasAddressAggregationClient = mock(NasAddressAggregationClient.class);
        properties = new UserDumpProperties();
        properties.setShards(1);
        properties.setTimezone("UTC");
        definition = new UserDataDumpReportDefinition(
                rowReader, nasAddressAggregationClient, properties, Runnable::run);
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
    void writesEachDatabaseColumnIntoItsAgreedPositionAndSplicesTheNasAddressIn() throws Exception {
        String[] dbRow = databaseRow("taiwowilliams", "FTTH_50Mbps", "107374182400", "70093948746");
        stubReader(List.<String[]>of(dbRow));
        stubNasAddresses(Map.of("taiwowilliams", "10.20.30.40"));

        Path output = outputWithHeader("dump.csv");
        long rows = definition.streamTo(report(), output);

        assertEquals(1, rows);
        List<String> lines = Files.readAllLines(output);
        assertEquals(EXPECTED_HEADER, lines.get(0));

        String[] written = lines.get(1).split(",", -1);
        assertEquals(39, written.length);
        assertEquals("taiwowilliams", written[0]);
        // Result set columns 1..30 land on CSV columns 1..30 untouched.
        assertEquals("col20", written[19], "MAC_ADDRESS");
        assertEquals("col22", written[21], "ORIGINAL_MAC_ADDRESS");
        assertEquals("taiwowilliams", written[28], "SLMN carries the username, which AAA_USER has no column for");
        assertEquals("col30", written[29], "VLAN_ID");
        // The NAS address is spliced in from Elasticsearch, and the database columns after it
        // shift back into place rather than trailing one position short.
        assertEquals("10.20.30.40", written[30], "NAS_IP_ADDRESS");
        assertEquals("col31", written[31],
                "NOTIFICATION_TEMPLATES, which the statement fills from TEMPLATE_ID");
        assertEquals("FTTH_50Mbps", written[35], "PLAN_BANDWIDTH");
        assertEquals("107374182400", written[36], "QUOTA");
        assertEquals("70093948746", written[37],
                "UTLIZED_QUOTA, the quota bucket's own USAGE, off the same cursor");
        assertEquals("bundle-end", written[38], "BUNDLE_DEACTIVATION_DATE");
    }

    @Test
    void joinsUpTheMacAddressesTheCursorHandsOutOneRowAtATime() throws Exception {
        // The statement joins AAA_USER_MAC_ADDRESS row for row, so a user with three addresses
        // arrives as three rows that differ only in those two columns.
        stubReader(List.of(
                macRow("taiwowilliams", "AA:AA", "BB:AA"),
                macRow("taiwowilliams", "AA:BB", "BB:BB"),
                macRow("taiwowilliams", "AA:CC", "BB:CC")));
        stubNasAddresses(Map.of("taiwowilliams", "10.0.0.1"));

        Path output = outputWithHeader("macs.csv");
        long rows = definition.streamTo(report(), output);

        assertEquals(1, rows, "the three rows are one user, and the dump is one row per user");
        List<String> lines = Files.readAllLines(output);
        assertEquals(2, lines.size(), "header plus the single collapsed row");

        // The joined lists carry commas, so the row has to be read back as CSV rather than split.
        List<String> written = fields(lines.get(1));
        assertEquals(39, written.size());
        assertEquals("AA:AA,AA:BB,AA:CC", written.get(19), "MAC_ADDRESS");
        assertEquals("BB:AA,BB:BB,BB:CC", written.get(21), "ORIGINAL_MAC_ADDRESS");
        assertEquals("10.0.0.1", written.get(30), "the columns after the lists must not shift");
        assertEquals("512", written.get(37), "UTLIZED_QUOTA");
    }

    @Test
    void probesElasticsearchOncePerUserRatherThanOncePerMacAddress() throws Exception {
        // The cursor hands each username out exactly once; a second probe for a user it has
        // already passed would come back empty and blank the spliced column.
        stubReader(List.of(
                macRow("firstuser", "AA:AA", "BB:AA"),
                macRow("firstuser", "AA:BB", "BB:BB"),
                macRow("seconduser", "CC:CC", "DD:DD")));
        stubNasAddresses(Map.of("firstuser", "10.0.0.1", "seconduser", "10.0.0.2"));

        Path output = outputWithHeader("probes.csv");
        assertEquals(2, definition.streamTo(report(), output));

        List<String> lines = Files.readAllLines(output);
        assertEquals("10.0.0.1", fields(lines.get(1)).get(30));
        assertEquals("10.0.0.2", fields(lines.get(2)).get(30));
    }

    @Test
    void aUserHoldingNoMacAddressStillGetsARowWithBothColumnsEmpty() throws Exception {
        // The outer join gives such a user a single row with both MAC columns null.
        stubReader(List.<String[]>of(macRow("nomacuser", null, null)));
        stubNasAddresses(Map.of());

        Path output = outputWithHeader("no-mac.csv");
        assertEquals(1, definition.streamTo(report(), output));

        String[] written = Files.readAllLines(output).get(1).split(",", -1);
        assertEquals(39, written.length);
        assertEquals("nomacuser", written[0]);
        assertEquals("", written[19], "MAC_ADDRESS");
        assertEquals("", written[21], "ORIGINAL_MAC_ADDRESS");
        assertEquals("bundle-end", written[38], "the columns after them must not shift");
    }

    @Test
    void cutsBothMacListsAtTheSameAddressOnceTheBudgetIsSpent() throws Exception {
        // 4 000 bytes is the budget, and each row is charged the wider of its two addresses plus a
        // separator. A 199 byte address therefore costs 200, and the twenty-first one is dropped.
        String longMac = "A".repeat(199);
        String longOriginal = "B".repeat(150);
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(macRow("busyuser", longMac, longOriginal));
        }
        stubReader(rows);
        stubNasAddresses(Map.of());

        Path output = outputWithHeader("budget.csv");
        assertEquals(1, definition.streamTo(report(), output));

        String line = Files.readAllLines(output).get(1);
        assertEquals(20, countOccurrences(line, longMac), "the budget stops the list at twenty addresses");
        assertEquals(20, countOccurrences(line, longOriginal),
                "both lists must be cut at the same address so the two columns stay aligned");
    }

    /** Splits one CSV line into its fields, honouring the quoting the writer applies. */
    private static List<String> fields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                field.append('"');
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @Test
    void aUserWhoseSessionsNamedNoNasLeavesTheColumnEmptyWithoutShiftingTheRow() throws Exception {
        stubReader(List.<String[]>of(databaseRow("taiwowilliams", "FTTH_50Mbps", "1024", "5")));
        stubNasAddresses(sessionWithoutNas("taiwowilliams"));

        Path output = outputWithHeader("no-nas.csv");
        definition.streamTo(report(), output);

        String[] written = Files.readAllLines(output).get(1).split(",", -1);
        assertEquals(39, written.length);
        assertEquals("", written[30], "NAS_IP_ADDRESS");
        assertEquals("col31", written[31], "NOTIFICATION_TEMPLATES");
        assertEquals("5", written[37], "UTLIZED_QUOTA comes off the cursor, not from this lookup");
    }

    @Test
    void anUnlimitedBundleReportsAnEmptyQuotaRatherThanALabel() throws Exception {
        // UserDumpSql leaves QUOTA null for an unlimited data bucket; it must reach the CSV as an
        // empty column, without disturbing the ones around it — and the bucket has still recorded
        // what it has recorded, which is what the BUCKET_INSTANCE extract reports for it too.
        stubReader(List.<String[]>of(databaseRow("unlimiteduser", "FTTH_50Mbps", null, "70093948746")));
        stubNasAddresses(Map.of());

        Path output = outputWithHeader("unlimited.csv");
        definition.streamTo(report(), output);

        String[] written = Files.readAllLines(output).get(1).split(",", -1);
        assertEquals(39, written.length);
        assertEquals("FTTH_50Mbps", written[35], "PLAN_BANDWIDTH");
        assertEquals("", written[36], "QUOTA");
        assertEquals("70093948746", written[37], "UTLIZED_QUOTA");
    }

    @Test
    void writesTheUsageExactlyAsTheDatabaseProducedIt() throws Exception {
        // The point of the column: a row of this dump is read against the BUCKET_INSTANCE extract's
        // row for the same bucket, and both write what getString made of the same NUMBER. Nothing
        // here may parse, round or reformat it on the way past.
        stubReader(List.of(
                databaseRow("bigdrawer", "FTTH_50Mbps", "107374182400", "9223372036854775808"),
                databaseRow("fractionaldrawer", "FTTH_50Mbps", "1024", "0.5")));
        stubNasAddresses(Map.of());

        Path output = outputWithHeader("verbatim.csv");
        assertEquals(2, definition.streamTo(report(), output));

        List<String> lines = Files.readAllLines(output);
        assertEquals("9223372036854775808", lines.get(1).split(",", -1)[37],
                "past what a long holds, and still the database's own text");
        assertEquals("0.5", lines.get(2).split(",", -1)[37], "not rounded to a whole number");
    }

    @Test
    void aBundleWithNoQuotaBucketLeavesTheColumnEmptyRatherThanReportingAZero() throws Exception {
        // The outer join gives such a user a null there, and an empty column says "no bucket to
        // report" — which is what the BUCKET_INSTANCE extract says about them too, by having no
        // row for them at all. A 0 would say the bucket exists and has not been drawn on.
        stubReader(List.<String[]>of(databaseRow("nobundleuser", null, null, null)));
        stubNasAddresses(Map.of("nobundleuser", "10.20.30.40"));

        Path output = outputWithHeader("no-bucket.csv");
        assertEquals(1, definition.streamTo(report(), output));

        String[] written = Files.readAllLines(output).get(1).split(",", -1);
        assertEquals(39, written.length);
        assertEquals("", written[36], "QUOTA");
        assertEquals("", written[37], "UTLIZED_QUOTA");
        assertEquals("bundle-end", written[38], "the columns after it must not shift");
    }

    @Test
    void usersWithoutSessionsThatDayGetAnEmptyNasAddressButKeepTheirUsage() throws Exception {
        // The two columns no longer come from the same place: a subscriber the CDR indices have
        // never heard of still has a bucket, and the dump still reports what it says.
        stubReader(List.<String[]>of(databaseRow("quietuser", "FTTH_50Mbps", "1024", "0")));
        stubNasAddresses(Map.of());

        Path output = outputWithHeader("quiet.csv");
        definition.streamTo(report(), output);

        String[] written = Files.readAllLines(output).get(1).split(",", -1);
        assertEquals("", written[30], "NAS_IP_ADDRESS");
        assertEquals("0", written[37], "UTLIZED_QUOTA");
        assertEquals("bundle-end", written[38], "the columns after them must not shift");
    }

    @Test
    void rendersEveryTimestampColumnUnderTheOneConfiguredModel() throws Exception {
        // The shipped model, pinned here rather than left to whoever next edits application.yml.
        // It is not what makes the dump readable in a spreadsheet — see the test below for that —
        // but it is what makes the five columns agree with each other and with the header.
        assertEquals("YYYY-MM-DD HH24:MI:SS", new UserDumpProperties().getDateFormat());

        stubReader(List.of());
        stubNasAddresses(Map.of());

        definition.streamTo(report(), outputWithHeader("format.csv"));

        ArgumentCaptor<SqlStatement> statement = ArgumentCaptor.forClass(SqlStatement.class);
        verify(rowReader).streamInBinaryOrder(statement.capture(), anyInt(), anyInt(), any());

        String sql = statement.getValue().sql();
        assertEquals(5, countOccurrences(sql, "'YYYY-MM-DD HH24:MI:SS'"),
                "created, updated, customer activation, bundle activation and deactivation dates");
        assertFalse(sql.contains("FF"), "the shipped model carries no fractional seconds element");
    }

    @Test
    void writesTheFiveDateColumnsSoASpreadsheetDisplaysThemInsteadOfConvertingThem() throws Exception {
        // What the operator opened the dump and saw was 46271.07939 in every date column: Excel
        // reads a CSV timestamp as a date, replaces the text with the day number behind it and
        // shows the number. Dropping the milliseconds from the format model did not stop it. The
        // ="..." form does — it is a formula whose value is the string — and it is applied to
        // exactly the five columns the statement renders with TO_CHAR, not to the row.
        String[] dbRow = databaseRow("taiwowilliams", "FTTH_50Mbps", "1024", "5");
        dbRow[9] = "2026-09-06 01:54:19";                                    // CREATED_DATE
        dbRow[27] = "2026-09-06 01:54:19";                                   // UPDATED_DATE
        dbRow[31] = "2026-09-06 01:54:19";                                   // CUSTOMER_ACTIVATION_DATE
        dbRow[32] = "2026-09-10 12:44:38";                                   // BUNDLE_ACTIVATION_DATE
        dbRow[UserDumpSql.COL_BUNDLE_DEACTIVATION_DATE - 1] = "2026-12-09 12:44:38";
        stubReader(List.<String[]>of(dbRow));
        stubNasAddresses(Map.of("taiwowilliams", "10.20.30.40"));

        Path output = outputWithHeader("excel-safe.csv");
        definition.streamTo(report(), output);

        List<String> lines = Files.readAllLines(output);
        assertEquals(EXPECTED_HEADER, lines.get(0), "the header is a row of names, never rewritten");

        String[] written = lines.get(1).split(",", -1);
        assertEquals(39, written.length, "the formula carries no delimiter, so no column shifts");
        assertEquals("=\"2026-09-06 01:54:19\"", written[9], "CREATED_DATE");
        assertEquals("=\"2026-09-06 01:54:19\"", written[27], "UPDATED_DATE");
        assertEquals("=\"2026-09-06 01:54:19\"", written[32], "CUSTOMER_ACTIVATION_DATE");
        assertEquals("=\"2026-09-10 12:44:38\"", written[33], "BUNDLE_ACTIVATION_DATE");
        assertEquals("=\"2026-12-09 12:44:38\"", written[38], "BUNDLE_DEACTIVATION_DATE");
        // CYCLE_DATE is not rendered as a timestamp and must not be treated as one, and neither
        // must any column that merely sits beside a date.
        assertEquals("col12", written[11], "CYCLE_DATE");
        assertEquals("taiwowilliams", written[28], "SLMN");
        assertEquals("FTTH_50Mbps", written[35], "PLAN_BANDWIDTH");
    }

    @Test
    void aDeploymentWhoseModelStillCarriesMillisecondsGetsTheSameCell() throws Exception {
        // The displayed format is a property of the file, not of the configuration: whatever
        // date-format a deployment turns out to carry, the operator reads yyyy-MM-dd HH:mm:ss.
        properties.setDateFormat("YYYY-MM-DD HH24:MI:SS.FF3");
        String[] dbRow = databaseRow("taiwowilliams", "FTTH_50Mbps", "1024", "5");
        dbRow[9] = "2026-09-06 01:54:19.123";
        stubReader(List.<String[]>of(dbRow));
        stubNasAddresses(Map.of("taiwowilliams", "10.20.30.40"));

        Path output = outputWithHeader("fractional.csv");
        definition.streamTo(report(), output);

        String[] written = Files.readAllLines(output).get(1).split(",", -1);
        assertEquals("=\"2026-09-06 01:54:19\"", written[9], "CREATED_DATE");
    }

    @Test
    void switchingTheSpreadsheetFormOffWritesTheDatabasesOwnTextBack() throws Exception {
        // The ="..." is a spreadsheet formula, and a consumer that loads the dump with something
        // else wants the bare timestamp. Nothing but the five columns changes with the switch.
        properties.setExcelSafeTimestamps(false);
        String[] dbRow = databaseRow("taiwowilliams", "FTTH_50Mbps", "1024", "5");
        dbRow[9] = "2026-09-06 01:54:19";
        stubReader(List.<String[]>of(dbRow));
        stubNasAddresses(Map.of("taiwowilliams", "10.20.30.40"));

        Path output = outputWithHeader("plain.csv");
        definition.streamTo(report(), output);

        String[] written = Files.readAllLines(output).get(1).split(",", -1);
        assertEquals(39, written.length);
        assertEquals("2026-09-06 01:54:19", written[9], "CREATED_DATE");
    }

    @Test
    void theSpreadsheetFormIsOnUnlessADeploymentTurnsItOff() {
        assertTrue(new UserDumpProperties().isExcelSafeTimestamps());
    }

    @Test
    void looksUpTheNasAddressForTheDayBeforeTheRun() throws Exception {
        stubReader(List.of());
        stubNasAddresses(Map.of());

        definition.streamTo(report(), outputWithHeader("day.csv"));

        ArgumentCaptor<LocalDate> day = ArgumentCaptor.forClass(LocalDate.class);
        verify(nasAddressAggregationClient).open(day.capture(), eq(null), eq(null));
        assertEquals(LocalDate.now(ZoneId.of("UTC")).minusDays(1), day.getValue());
    }

    @Test
    void shardPartsAreConcatenatedInUsernameOrderRegardlessOfWhichShardFinishesFirst() throws Exception {
        properties.setShards(3);
        stubNasAddresses(Map.of());

        // Each shard emits a single row naming the range it was given, so the finished file shows
        // whether the parts were stitched back together in keyspace order.
        when(rowReader.streamInBinaryOrder(any(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            StreamingRowReader.RowHandler handler = invocation.getArgument(3);
            handler.handle(resultSetOf(databaseRow(shardInProgress.get(), "bw", "quota", "512")));
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

        verify(rowReader, times(3)).streamInBinaryOrder(any(), anyInt(), anyInt(), any());
        assertTrue(listPartFiles().isEmpty(), "part files must be cleaned up: " + listPartFiles());
    }

    @Test
    void eachShardAggregatesOnlyItsOwnUsernameRange() throws Exception {
        properties.setShards(3);
        stubReader(List.of());
        stubNasAddresses(Map.of());

        definition.streamTo(report(), outputWithHeader("ranges.csv"));

        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(nasAddressAggregationClient, times(3)).open(any(), from.capture(), to.capture());

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
        stubNasAddresses(Map.of());
        when(rowReader.streamInBinaryOrder(any(), anyInt(), anyInt(), any()))
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
        when(rowReader.streamInBinaryOrder(any(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            StreamingRowReader.RowHandler handler = invocation.getArgument(3);
            for (String[] row : rows) {
                handler.handle(resultSetOf(row));
            }
            return (long) rows.size();
        });
    }

    /**
     * Serves the Elasticsearch side from a fixed table, in the username order the join expects. A
     * user in it is one the aggregation returned — one who held a session on the reported day —
     * and the address they are mapped to may be null, which is a session that named no NAS.
     */
    private void stubNasAddresses(Map<String, String> nasByUser) {
        when(nasAddressAggregationClient.open(any(), any(), any())).thenAnswer(invocation -> {
            String from = invocation.getArgument(1);
            shardInProgress.set(from == null ? OPEN_START : from);

            List<String> users = new ArrayList<>(nasByUser.keySet());
            users.sort(String::compareTo);
            var iterator = users.iterator();
            return new UserNasAddressCursor() {
                @Override
                protected UserNasAddress fetchNext() {
                    if (!iterator.hasNext()) {
                        return null;
                    }
                    String user = iterator.next();
                    return new UserNasAddress(user, nasByUser.get(user));
                }
            };
        });
    }

    /** A one-user table carrying an address {@code Map.of} would refuse: none at all. */
    private static Map<String, String> sessionWithoutNas(String user) {
        Map<String, String> addresses = new HashMap<>();
        addresses.put(user, null);
        return addresses;
    }

    /**
     * A result set row. The statement selects nothing for NAS_IP_ADDRESS — that column is filled
     * from Elasticsearch — so the row is one column shorter than the CSV, and the username stands
     * in for SLMN the way the statement binds it.
     */
    private String[] databaseRow(String userName, String bandwidth, String quota, String utlizedQuota) {
        String[] values = new String[UserDumpSql.COL_BUNDLE_DEACTIVATION_DATE];
        for (int i = 0; i < values.length; i++) {
            values[i] = "col" + (i + 1);
        }
        values[0] = userName;
        values[28] = userName;
        values[34] = bandwidth;
        values[35] = quota;
        values[UserDumpSql.COL_UTLIZED_QUOTA - 1] = utlizedQuota;
        values[UserDumpSql.COL_BUNDLE_DEACTIVATION_DATE - 1] = "bundle-end";
        return values;
    }

    /** A row of the fan-out the MAC join produces: one user, one of their MAC addresses. */
    private String[] macRow(String userName, String macAddress, String originalMacAddress) {
        String[] values = databaseRow(userName, "FTTH_50Mbps", "1024", "512");
        values[19] = macAddress;
        values[21] = originalMacAddress;
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
