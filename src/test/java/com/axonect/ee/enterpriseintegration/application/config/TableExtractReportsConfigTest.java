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
 * figures cannot be told apart in the file — so the decision is worth holding to.
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
    void switchingItOffLeavesTheExtractTheSinglePlainScanItWas() throws Exception {
        properties.setUsageFromCdr(false);

        String sql = runBucketExtract();

        assertTrue(sql.endsWith("FROM BUCKET_INSTANCE b"));
        assertTrue(sql.contains("b.USAGE"));
        verify(rowReader, never()).streamInBinaryOrder(any(), anyInt(), anyInt(), any());
        verify(usageAggregationClient, never()).openBucketTotals(anyString(), anyString());
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

    /** Runs the configured bucket extract over an empty cursor and returns the statement it used. */
    private String runBucketExtract() throws Exception {
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
        return statement.getValue().sql();
    }

    private boolean readsTheCdrTotal() {
        return properties.isUsageFromCdr() && usageProperties.getUsage().isEnabled();
    }
}
