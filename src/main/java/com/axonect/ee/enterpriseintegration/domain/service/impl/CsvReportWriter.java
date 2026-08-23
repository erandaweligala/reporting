package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.service.ReportWriter;
import com.axonect.ee.enterpriseintegration.domain.util.GenericCsvExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CsvReportWriter implements ReportWriter {

    private final GenericCsvExporter genericCsvExporter;

    @Override
    public Path init(String reportName, List<CsvColumn> columns, String classificationLevel) throws IOException {
        return genericCsvExporter.createCsvFileWithHeaders(reportName, columns);
    }

    @Override
    public void writeBatch(Path filePath, List<CsvColumn> columns, List<Map<String, Object>> rows) throws IOException {
        genericCsvExporter.appendRowsToCsv(filePath, columns, rows);
    }
}
