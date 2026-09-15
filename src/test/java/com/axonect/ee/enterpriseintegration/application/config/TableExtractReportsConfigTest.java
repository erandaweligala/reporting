package com.axonect.ee.enterpriseintegration.application.config;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.client.UsageAggregationClient;
import com.axonect.ee.enterpriseintegration.domain.client.UserUsageCursor;
import com.axonect.ee.enterpriseintegration.domain.constant.SqlStatement;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.repository.StreamingRowReader;
import com.axonect.ee.enterpriseintegration.domain.service.impl.TableExtractReportDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where BUCKET_INSTANCE.USAGE is read from is decided once, when the bean is built, and the two
 * figures cannot be told apart in the file — so the decision is worth holding to. The same is true
 * of RULE, which reports the bundle's bandwidth bucket under whichever of the two variants runs.
 */
class TableExtractReportsConfigTest {

    @TempDir
    Path tempDir;

    private final TableExtractReportsConfig config = new TableExtractReportsConfig();
    private final TableExtractProperties properties = new TableExtractProperties();
    private final UserDumpProperties usageProperties = new UserDumpProperties();

    private StreamingRowReader rowReader;
    private UsageAggregationClient usageAggregationClient;

    @BeforeEach
    void setUp() throws Exception {
        rowReader = mock(StreamingRowReader.class);
        usageAggregationClient = mock(UsageAggregationClient.class);
        when(usageAggregationClient.openBucketTotals(any(), any())).thenReturn(UserUsageCursor.empty());
        when(rowReader.stream(any(), anyInt(), anyInt(), any())).thenReturn(0L);
        when(rowReader.streamInBinaryOrder(any(), anyInt(), anyInt(), any())).thenReturn(0L);
    }

    @Test
    void byDefaultTheBucketExtractReadsItsUsageFromTheCdrDocuments() throws Exception {
        String sql = runBucketExtract();

        assertFalse(sql.contains("b.USAGE"), "the table's own counter is not what is reported");
        assertTrue(sql.contains("si.USERNAME"),
                "the CDR figures are keyed on the username holding the bucket's service");
        verify(rowReader).streamInBinaryOrder(any(), anyInt(), anyInt(), any());
        verify(usageAggregationClient).openBucketTotals(null, null);
    }

    @Test
    void switchingItOffLeavesTheExtractTheUnorderedScanItWas() throws Exception {
        properties.setUsageFromCdr(false);

        String sql = runBucketExtract();

        assertTrue(sql.endsWith("ON bw.SERVICE_ID = b.SERVICE_ID"),
                "the table, and the bandwidth bucket RULE reports — no ordering, nothing else");
        assertFalse(sql.contains("ORDER BY"));
        assertTrue(sql.contains("b.USAGE"));
        verify(rowReader, never()).streamInBinaryOrder(any(), anyInt(), anyInt(), any());
        verify(usageAggregationClient, never()).openBucketTotals(anyString(), anyString());
    }

    @Test
    void ruleReportsThePlanBandwidthBucketWhicheverWayUsageIsRead() throws Exception {
        // The consuming system reads one BUCKET_INSTANCE file, not two. Where USAGE comes from is
        // a deployment's business and is not visible in the file, so it cannot decide what a
        // second column means.
        assertStatement(statement -> {
            assertTrue(statement.sql().contains("bw.PLAN_BANDWIDTH"),
                    "RULE is the bundle's bandwidth bucket");
            assertFalse(statement.sql().contains("b.RULE"),
                    "and not the table's own RULE column");
            assertEquals(List.of(usageProperties.getBandwidthBucketType()), statement.params(),
                    "under the bucket type the dump reads PLAN_BANDWIDTH under");
        });

        // The same bean built the other way round; the two verifications are of different
        // methods of the reader, so the mock does not need resetting between them.
        properties.setUsageFromCdr(false);

        assertStatement(statement -> {
            assertTrue(statement.sql().contains("bw.PLAN_BANDWIDTH"));
            assertFalse(statement.sql().contains("b.RULE"));
            assertEquals(List.of(usageProperties.getBandwidthBucketType()), statement.params());
        });
    }

    @Test
    void theBucketTypeRuleIsReadUnderIsTheDumpsOwnSetting() throws Exception {
        // One setting, two reports: a deployment that renames the bucket type moves PLAN_BANDWIDTH
        // and RULE together, because there is nowhere else to say it.
        usageProperties.setBandwidthBucketType("FTTH_BANDWIDTH");

        assertStatement(statement ->
                assertEquals(List.of("FTTH_BANDWIDTH"), statement.params()));
    }

    @Test
    void anElasticsearchLookupThatIsOffFallsBackToTheColumnRatherThanEmptyingIt() throws Exception {
        usageProperties.getUsage().setEnabled(false);

        assertTrue(runBucketExtract().contains("b.USAGE"),
                "every row would otherwise report an empty USAGE");
    }

    @Test
    void aFlattenedSessionInstancesMappingStillReportsTheFigureTheDumpReports() throws Exception {
        // Elasticsearch flattens an array that is not mapped as nested, so the only figure it can
        // give back is the subscriber's whole usage. That is exactly what the dump reports for them
        // as UTLIZED_QUOTA, so the extract reports it too — against one bucket of theirs rather
        // than all of them. Falling back to the table's own counter here is what left USAGE at 0
        // beside a UTLIZED_QUOTA read from the CDRs for the same subscriber.
        usageProperties.getUsage().setNested(false);

        String sql = runBucketExtract();

        assertFalse(sql.contains("b.USAGE"), "the table's own counter is a different figure");
        verify(usageAggregationClient).openBucketTotals(null, null);
    }

    @Test
    void theOtherTwoExtractsAreUntouchedByAnyOfIt() {
        assertEquals("MAC_SERVICE_TABLE",
                config.macServiceTableReportDefinition(rowReader, properties).reportType());
        assertEquals("PLAN_TO_BUCKET",
                config.planToBucketReportDefinition(rowReader, properties).reportType());
    }

    /** Runs the configured bucket extract over an empty cursor and checks the statement it used. */
    private void assertStatement(Consumer<SqlStatement> assertions) throws Exception {
        assertions.accept(runBucketExtractStatement());
    }

    /** As {@link #assertStatement}, for an assertion that only reads the text. */
    private String runBucketExtract() throws Exception {
        return runBucketExtractStatement().sql();
    }

    private SqlStatement runBucketExtractStatement() throws Exception {
        TableExtractReportDefinition definition = config.bucketInstanceReportDefinition(
                rowReader, properties, usageProperties, usageAggregationClient);
        assertEquals("BUCKET_INSTANCE", definition.reportType());

        Path output = tempDir.resolve("bucket.csv");
        Files.writeString(output, definition.columns().stream()
                .map(CsvColumn::getLabel)
                .collect(Collectors.joining(",")) + System.lineSeparator());

        DownloadReport report = new DownloadReport();
        report.setId(4242L);
        definition.streamTo(report, output);

        ArgumentCaptor<SqlStatement> statement = ArgumentCaptor.forClass(SqlStatement.class);
        if (readsTheCdrTotal()) {
            verify(rowReader).streamInBinaryOrder(statement.capture(), anyInt(), anyInt(), any());
        } else {
            verify(rowReader).stream(statement.capture(), anyInt(), anyInt(), any());
        }
        return statement.getValue();
    }

    private boolean readsTheCdrTotal() {
        return properties.isUsageFromCdr() && usageProperties.getUsage().isEnabled();
    }
}
