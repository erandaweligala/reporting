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

class GenericExcelExporterTest {

    @TempDir
    Path tempDir;

    private GenericExcelExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new GenericExcelExporter();
        ReflectionTestUtils.setField(exporter, "outputDirectory", tempDir.toString());
    }

    @Test
    void testCreateExcelFile_whenDirectoryDoesNotExist() throws IOException {
        Path outputDir = tempDir.resolve("excel");
        ReflectionTestUtils.setField(exporter, "outputDirectory", outputDir.toString());

        List<CsvColumn> columns = List.of(
                new CsvColumn("id", "ID"),
                new CsvColumn("name", "Name")
        );

        Path filePath = exporter.createExcelFileWithHeaders("report", "public",columns);

        assertTrue(Files.exists(outputDir));
        assertNotNull(filePath);
    }

    @Test
    void testCreateExcelFile_whenDirectoryAlreadyExists() throws IOException {
        Files.createDirectories(tempDir);

        List<CsvColumn> columns = List.of(
                new CsvColumn("id", "ID")
        );

        Path filePath = exporter.createExcelFileWithHeaders("report","public", columns);

        assertTrue(filePath.toString().endsWith(".xlsx"));
    }

    @Test
    void testAppendRows_success() throws IOException {
        List<CsvColumn> columns = List.of(
                new CsvColumn("id", "ID"),
                new CsvColumn("name", "Name")
        );

        Path filePath = exporter.createExcelFileWithHeaders("report", "public",columns);

        exporter.appendRowsToExcel(
                filePath,
                columns,
                List.of(
                        Map.of("id", 1, "name", "John"),
                        Map.of("id", 2)
                )
        );

        // No exception = branch covered
        assertTrue(true);
    }

    @Test
    void testAppendRows_whenWorkbookNotInitialized() {
        Path fakePath = tempDir.resolve("missing.xlsx");

        List<CsvColumn> columns = List.of(
                new CsvColumn("id", "ID")
        );

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> exporter.appendRowsToExcel(fakePath, columns, List.of(Map.of("id", 1)))
        );

        assertTrue(ex.getMessage().contains("Workbook not initialized"));
    }

    @Test
    void testSaveAndClose_success() throws IOException {
        List<CsvColumn> columns = List.of(
                new CsvColumn("id", "ID")
        );

        Path filePath = exporter.createExcelFileWithHeaders("report","public", columns);

        exporter.appendRowsToExcel(
                filePath,
                columns,
                List.of(Map.of("id", "123"))
        );

        exporter.saveAndClose(filePath);

        assertTrue(Files.exists(filePath));
        assertTrue(Files.size(filePath) > 0);
    }

    @Test
    void testSaveAndClose_whenContextIsNull() throws IOException {
        Path fakePath = tempDir.resolve("not-exists.xlsx");

        exporter.saveAndClose(fakePath);

        // no exception = branch covered
        assertTrue(true);
    }
}
