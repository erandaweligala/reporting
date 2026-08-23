package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GenericCsvExporterTest {

    @TempDir
    Path tempDir;

    private GenericCsvExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new GenericCsvExporter();
        ReflectionTestUtils.setField(exporter, "csvOutputDirectory", tempDir.toString());
    }

    @Test
    void testCreateCsvFile_whenDirectoryDoesNotExist() throws IOException {
        Path outputDir = tempDir.resolve("csv");
        ReflectionTestUtils.setField(exporter, "csvOutputDirectory", outputDir.toString());

        List<CsvColumn> columns = List.of(
                new CsvColumn("id", "ID"),
                new CsvColumn("name", "Name")
        );

        Path filePath = exporter.createCsvFileWithHeaders("report", columns);

        assertTrue(Files.exists(outputDir));
        assertTrue(Files.exists(filePath));
        assertTrue(Files.readString(filePath).startsWith("ID,Name"));
    }

    @Test
    void testCreateCsvFile_whenDirectoryAlreadyExists() throws IOException {
        Files.createDirectories(tempDir);

        List<CsvColumn> columns = List.of(
                new CsvColumn("id", "ID")
        );

        Path filePath = exporter.createCsvFileWithHeaders("report", columns);

        assertTrue(Files.exists(filePath));
    }

    @Test
    void testAppendRows_withNullValue() throws IOException {
        List<CsvColumn> columns = List.of(
                new CsvColumn("id", "ID"),
                new CsvColumn("name", "Name")
        );

        Path filePath = exporter.createCsvFileWithHeaders("report", columns);

        exporter.appendRowsToCsv(
                filePath,
                columns,
                List.of(Map.of("id", 1))
        );

        String content = Files.readString(filePath);
        assertTrue(content.contains("1,"));
    }

    @Test
    void testEscapeDateValue() throws IOException {
        List<CsvColumn> columns = List.of(
                new CsvColumn("date", "Date")
        );

        Path filePath = exporter.createCsvFileWithHeaders("report", columns);

        exporter.appendRowsToCsv(
                filePath,
                columns,
                List.of(Map.of("date", "2024-01-15"))
        );

        String content = Files.readString(filePath);
        assertTrue(content.contains("=\"2024-01-15\""));
    }

    @Test
    void testEscapeCommaValue() throws IOException {
        List<CsvColumn> columns = List.of(
                new CsvColumn("text", "Text")
        );

        Path filePath = exporter.createCsvFileWithHeaders("report", columns);

        exporter.appendRowsToCsv(
                filePath,
                columns,
                List.of(Map.of("text", "hello,world"))
        );

        String content = Files.readString(filePath);
        assertTrue(content.contains("\"hello,world\""));
    }

    @Test
    void testEscapeQuoteValue() throws IOException {
        List<CsvColumn> columns = List.of(
                new CsvColumn("text", "Text")
        );

        Path filePath = exporter.createCsvFileWithHeaders("report", columns);

        exporter.appendRowsToCsv(
                filePath,
                columns,
                List.of(Map.of("text", "he said \"hi\""))
        );

        String content = Files.readString(filePath);
        assertTrue(content.contains("\"he said \"\"hi\"\"\""));
    }

    @Test
    void testEscapeNewLineValue() throws IOException {
        List<CsvColumn> columns = List.of(
                new CsvColumn("text", "Text")
        );

        Path filePath = exporter.createCsvFileWithHeaders("report", columns);

        exporter.appendRowsToCsv(
                filePath,
                columns,
                List.of(Map.of("text", "line1\nline2"))
        );

        String content = Files.readString(filePath);
        assertTrue(content.contains("\"line1\nline2\""));
    }

    @Test
    void testPlainValue_noEscaping() throws IOException {
        List<CsvColumn> columns = List.of(
                new CsvColumn("text", "Text")
        );

        Path filePath = exporter.createCsvFileWithHeaders("report", columns);

        exporter.appendRowsToCsv(
                filePath,
                columns,
                List.of(Map.of("text", "plainText"))
        );

        String content = Files.readString(filePath);
        assertTrue(content.contains("plainText"));
    }
}

