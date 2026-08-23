package com.axonect.ee.enterpriseintegration.domain.service;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface ReportWriter {
    Path init(String reportName, List<CsvColumn> columns, String classificationLevel) throws IOException;

    void writeBatch(
            Path filePath,
            List<CsvColumn> columns,
            List<Map<String, Object>> rows
    ) throws IOException;
}
