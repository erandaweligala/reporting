package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.util.GenericExcelExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ExcelReportWriterTest {

    @Mock
    private GenericExcelExporter genericExcelExporter;

    private ExcelReportWriter excelReportWriter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        excelReportWriter = new ExcelReportWriter(genericExcelExporter);
    }

    @Test
    void init_ShouldCallGenericExcelExporterCreateExcelFileWithHeaders() throws IOException {
        Path mockPath = Path.of("test.xlsx");
        List<CsvColumn> columns = List.of(new CsvColumn("col1", "Column 1"));

        when(genericExcelExporter.createExcelFileWithHeaders("report","public", columns)).thenReturn(mockPath);

        Path result = excelReportWriter.init("report", columns,"public");

        assertEquals(mockPath, result);
        verify(genericExcelExporter, times(1)).createExcelFileWithHeaders("report","public", columns);
    }

    @Test
    void writeBatch_ShouldCallGenericExcelExporterAppendRowsToExcel() throws IOException {
        Path filePath = Path.of("report.xlsx");
        List<CsvColumn> columns = List.of(new CsvColumn("col1", "Column 1"));
        List<Map<String, Object>> rows = List.of(Map.of("col1", "value1"));

        excelReportWriter.writeBatch(filePath, columns, rows);

        verify(genericExcelExporter, times(1)).appendRowsToExcel(filePath, columns, rows);
    }

    @Test
    void close_ShouldCallGenericExcelExporterSaveAndClose() throws IOException {
        Path filePath = Path.of("report.xlsx");

        excelReportWriter.close(filePath);

        verify(genericExcelExporter, times(1)).saveAndClose(filePath);
    }
}
