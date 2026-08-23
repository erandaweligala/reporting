package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.service.ReportWriter;
import com.axonect.ee.enterpriseintegration.domain.util.GenericExcelExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExcelReportWriter implements ReportWriter {

    private final GenericExcelExporter genericExcelExporter;

    @Override
    public Path init(String reportName, List<CsvColumn> columns, String classificationLevel) throws IOException {
        return genericExcelExporter.createExcelFileWithHeaders(reportName, classificationLevel, columns);
    }

    @Override
    public void writeBatch(Path filePath, List<CsvColumn> columns, List<Map<String, Object>> rows) throws IOException {
        genericExcelExporter.appendRowsToExcel(filePath, columns, rows);
    }

    public void close(Path filePath) throws IOException {
        genericExcelExporter.saveAndClose(filePath);
    }
}