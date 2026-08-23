package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Slf4j
public class GenericCsvExporter {

    @SuppressWarnings("unused")
    @Value("${csv.output.directory}")
    private String csvOutputDirectory;

    public Path createCsvFileWithHeaders(
            String reportName,
            List<CsvColumn> columns
    ) throws IOException {

        Path directory = Paths.get(csvOutputDirectory);
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
        }

        String timestamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date());
        String fileName = reportName + "_" + timestamp + ".csv";
        Path filePath = directory.resolve(fileName);

        // Build header line
        List<String> headerLabels = columns.stream()
                .map(CsvColumn::getLabel)
                .collect(Collectors.toList());

        String headerLine = String.join(",", headerLabels) + System.lineSeparator();

        Files.writeString(
                filePath,
                headerLine,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        log.info("CSV report initialized at {}", filePath);

        return filePath;
    }

    public void appendRowsToCsv(
            Path filePath,
            List<CsvColumn> columns,
            List<Map<String, Object>> rows
    ) throws IOException {

        StringBuilder csvBuilder = new StringBuilder();

        for (Map<String, Object> row : rows) {
            List<String> rowValues = columns.stream()
                    .map(col -> escapeCsvValue(
                            Objects.toString(row.get(col.getKey()), "")
                    ))
                    .collect(Collectors.toList());

            csvBuilder
                    .append(String.join(",", rowValues))
                    .append(System.lineSeparator());
        }

        Files.writeString(
                filePath,
                csvBuilder.toString(),
                StandardOpenOption.APPEND
        );
    }

    private String escapeCsvValue(String value) {

        // Force Excel to treat dates as strings
        if (value.matches("\\d{4}[-/]\\d{2}[-/]\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2})?)?")) {
            return "=\"" + value + "\"";
        }

        if (value.contains("\"") || value.contains(",") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }
}


