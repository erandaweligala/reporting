package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.util.GenericCsvExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsvReportWriterTest {

    @Mock
    private GenericCsvExporter genericCsvExporter;

    @InjectMocks
    private CsvReportWriter csvReportWriter;

    @Test
    void init_ShouldCreateCsvFileWithHeaders() throws IOException {
        // Given
        String reportName = "test-report";
        List<CsvColumn> columns = Collections.emptyList();
        Path expectedPath = Path.of("test.csv");

        when(genericCsvExporter.createCsvFileWithHeaders(reportName, columns))
                .thenReturn(expectedPath);

        // When
        Path actualPath = csvReportWriter.init(reportName, columns,"public");

        // Then
        assertEquals(expectedPath, actualPath);
        verify(genericCsvExporter).createCsvFileWithHeaders(reportName, columns);
        verifyNoMoreInteractions(genericCsvExporter);
    }

    @Test
    void init_WhenExporterThrowsIOException_ShouldPropagate() throws IOException {
        // Given
        when(genericCsvExporter.createCsvFileWithHeaders(anyString(), anyList()))
                .thenThrow(new IOException("Disk error"));

        // Then
        assertThrows(IOException.class,
                () -> csvReportWriter.init("report", Collections.emptyList(),"public"));
    }

    @Test
    void writeBatch_ShouldAppendRowsToCsv() throws IOException {
        // Given
        Path filePath = Path.of("test.csv");
        List<CsvColumn> columns = Collections.emptyList();
        List<Map<String, Object>> rows = Collections.emptyList();

        // When
        csvReportWriter.writeBatch(filePath, columns, rows);

        // Then
        verify(genericCsvExporter)
                .appendRowsToCsv(filePath, columns, rows);
        verifyNoMoreInteractions(genericCsvExporter);
    }

    @Test
    void writeBatch_WhenExporterThrowsIOException_ShouldPropagate() throws IOException {
        // Given
        doThrow(new IOException("Write failed"))
                .when(genericCsvExporter)
                .appendRowsToCsv(any(), anyList(), anyList());

        // Then
        assertThrows(IOException.class,
                () -> csvReportWriter.writeBatch(
                        Path.of("test.csv"),
                        Collections.emptyList(),
                        Collections.emptyList()));
    }
}
