package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.config.TableExtractProperties;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.constant.SqlStatement;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Column;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtracts;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.repository.StreamingRowReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TableExtractReportDefinitionTest {

    private static final Spec SPEC = new Spec("SAMPLE", "SAMPLE_TABLE t", List.of(
            Column.plain("ID", "t.ID"),
            Column.plain("NAME", "t.NAME"),
            Column.at("CREATED_AT", "t.CREATED_AT")));

    @TempDir
    Path tempDir;

    private StreamingRowReader rowReader;
    private TableExtractProperties properties;
    private TableExtractReportDefinition definition;

    @BeforeEach
    void setUp() {
        rowReader = mock(StreamingRowReader.class);
        properties = new TableExtractProperties();
        definition = new TableExtractReportDefinition(SPEC, rowReader, properties);
    }

    @Test
    void columnsCarryTheSpecsHeadersInOrder() {
        assertEquals("ID,NAME,CREATED_AT", header(definition));
    }

    @Test
    void eachExtractIsRegisteredUnderItsOwnReportType() {
        assertEquals("SAMPLE", definition.reportType());
        assertEquals("MAC_SERVICE_TABLE", extract(TableExtracts.MAC_SERVICE_TABLE).reportType());
        assertEquals("PLAN_TO_BUCKET", extract(TableExtracts.PLAN_TO_BUCKET).reportType());
        assertEquals("BUCKET_INSTANCE", extract(TableExtracts.BUCKET_INSTANCE).reportType());
    }

    @Test
    void writesOneCsvRowPerDatabaseRowUnderTheHeaderAlreadyInTheFile() throws Exception {
        stubReader(List.of(
                new String[]{"54320387", "Data709", "2026-08-18 14:31:23.762"},
                new String[]{"54320388", "Data710", "2026-08-19 09:02:11.004"}));

        Path output = outputWithHeader("sample.csv");
        long rows = definition.streamTo(report(), output);

        assertEquals(2, rows);
        assertEquals(List.of(
                        "ID,NAME,CREATED_AT",
                        "54320387,Data709,2026-08-18 14:31:23.762",
                        "54320388,Data710,2026-08-19 09:02:11.004"),
                Files.readAllLines(output));
    }

    @Test
    void aNullColumnBecomesAnEmptyFieldWithoutShiftingTheOnesAroundIt() throws Exception {
        stubReader(List.<String[]>of(new String[]{"712", null, "2026-08-14 03:57:43.623"}));

        Path output = outputWithHeader("nulls.csv");
        definition.streamTo(report(), output);

        assertEquals("712,,2026-08-14 03:57:43.623", Files.readAllLines(output).get(1));
    }

    @Test
    void aValueCarryingTheDelimiterIsQuotedRatherThanSplittingTheRow() throws Exception {
        stubReader(List.<String[]>of(
                new String[]{"1", "FTTH-Internet (Up to 50 Mbps), tiered", "2026-08-14 03:57:43.623"}));

        Path output = outputWithHeader("quoting.csv");
        definition.streamTo(report(), output);

        assertEquals("1,\"FTTH-Internet (Up to 50 Mbps), tiered\",2026-08-14 03:57:43.623",
                Files.readAllLines(output).get(1));
    }

    @Test
    void everyColumnIsReadAsTextSoNoTimestampOrDecimalIsAllocatedPerRow() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(anyInt())).thenReturn("x");

        when(rowReader.stream(any(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, StreamingRowReader.RowHandler.class).handle(resultSet);
            return 1L;
        });

        definition.streamTo(report(), outputWithHeader("typed.csv"));

        verify(resultSet, never()).getTimestamp(anyInt());
        verify(resultSet, never()).getBigDecimal(anyInt());
        verify(resultSet, never()).getObject(anyInt());
    }

    @Test
    void streamsOnOneCursorUnderTheConfiguredFetchSizeAndTimeout() throws Exception {
        properties.setJdbcFetchSize(7500);
        properties.setQueryTimeoutSeconds(600);
        stubReader(List.of());

        definition.streamTo(report(), outputWithHeader("cursor.csv"));

        ArgumentCaptor<SqlStatement> statement = ArgumentCaptor.forClass(SqlStatement.class);
        verify(rowReader).stream(statement.capture(), eq(7500), eq(600), any());
        assertTrue(statement.getValue().sql().startsWith("SELECT t.ID, t.NAME,"));

        // Binary collation exists for the user dump's merge join against Elasticsearch. An extract
        // has no ordering to agree with, so it must not pay for the extra round trip.
        verify(rowReader, never()).streamInBinaryOrder(any(), anyInt(), anyInt(), any());
    }

    @Test
    void anEmptyTableWritesNoRowsSoTheRunIsRecordedAsNoRecords() throws Exception {
        stubReader(List.of());

        Path output = outputWithHeader("empty.csv");

        assertEquals(0, definition.streamTo(report(), output));
        assertEquals(List.of("ID,NAME,CREATED_AT"), Files.readAllLines(output));
    }

    @Test
    void aFailedCursorSurfacesAsTheRunsFailureRatherThanAShortFile() throws Exception {
        // Everything written before the failure is flushed by the writer's close, but the count is
        // never returned — the pipeline fails the report instead of recording a truncated extract
        // as Completed.
        when(rowReader.stream(any(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, StreamingRowReader.RowHandler.class)
                    .handle(resultSetOf(new String[]{"1", "first", "2026-08-14 03:57:43.623"}));
            throw new IllegalStateException("ORA-01555: snapshot too old");
        });

        Path output = outputWithHeader("failed.csv");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> definition.streamTo(report(), output));
        assertEquals("ORA-01555: snapshot too old", failure.getMessage());
        assertEquals(List.of("ID,NAME,CREATED_AT", "1,first,2026-08-14 03:57:43.623"),
                Files.readAllLines(output), "the writer must still have been closed and flushed");
    }

    @Test
    void pagedFetchingIsRefusedRatherThanQuietlyDegrading() {
        assertThrows(UnsupportedOperationException.class,
                () -> definition.fetchBatch(report(), 0, 200));
    }

    private TableExtractReportDefinition extract(Spec spec) {
        return new TableExtractReportDefinition(spec, rowReader, properties);
    }

    private void stubReader(List<String[]> rows) throws Exception {
        when(rowReader.stream(any(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            StreamingRowReader.RowHandler handler = invocation.getArgument(3);
            for (String[] row : rows) {
                handler.handle(resultSetOf(row));
            }
            return (long) rows.size();
        });
    }

    private ResultSet resultSetOf(String[] values) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        for (int i = 0; i < values.length; i++) {
            when(resultSet.getString(i + 1)).thenReturn(values[i]);
        }
        return resultSet;
    }

    /** The pipeline hands the definition a file that already carries the header line. */
    private Path outputWithHeader(String name) throws Exception {
        Path output = tempDir.resolve(name);
        Files.writeString(output, header(definition) + System.lineSeparator());
        return output;
    }

    private static String header(TableExtractReportDefinition definition) {
        return definition.columns().stream().map(CsvColumn::getLabel).collect(Collectors.joining(","));
    }

    private DownloadReport report() {
        DownloadReport report = new DownloadReport();
        report.setId(4242L);
        report.setReportType(SPEC.reportType());
        report.setFormat("CSV");
        return report;
    }
}
