package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingCsvWriterTest {

    @TempDir
    Path tempDir;

    private static final List<CsvColumn> COLUMNS = List.of(
            new CsvColumn("a", "A"),
            new CsvColumn("b", "B"),
            new CsvColumn("c", "C")
    );

    @Test
    void writesHeaderInColumnOrder() throws IOException {
        Path file = tempDir.resolve("out.csv");
        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, COLUMNS, 1024)) {
            // header only
        }
        assertEquals("A,B,C\r\n", Files.readString(file));
    }

    @Test
    void writesRowsInOrderAndCountsThem() throws IOException {
        Path file = tempDir.resolve("out.csv");
        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, COLUMNS, 1024)) {
            writer.writeRow(new String[]{"1", "2", "3"});
            writer.writeRow(new String[]{"4", "5", "6"});
            assertEquals(2, writer.getRowsWritten());
        }
        assertEquals("A,B,C\r\n1,2,3\r\n4,5,6\r\n", Files.readString(file));
    }

    @Test
    void nullAndEmptyValuesBecomeEmptyFields() throws IOException {
        Path file = tempDir.resolve("out.csv");
        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, COLUMNS, 1024)) {
            writer.writeRow(new String[]{null, "", "x"});
        }
        assertTrue(Files.readString(file).endsWith(",,x\r\n"));
    }

    @Test
    void quotesOnlyValuesThatNeedIt() throws IOException {
        Path file = tempDir.resolve("out.csv");
        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, COLUMNS, 1024)) {
            writer.writeRow(new String[]{"has,comma", "plain", "line\nbreak"});
            writer.writeRow(new String[]{"say \"hi\"", "a b", "c"});
        }

        List<String> lines = Files.readAllLines(file);
        // The embedded newline keeps the second field of row one on its own physical line.
        assertEquals("\"has,comma\",plain,\"line", lines.get(1));
        assertEquals("break\"", lines.get(2));
        assertEquals("\"say \"\"hi\"\"\",a b,c", lines.get(3));
    }

    @Test
    void macAddressListIsQuotedAsOneField() throws IOException {
        Path file = tempDir.resolve("out.csv");
        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, COLUMNS, 1024)) {
            writer.writeRow(new String[]{"u1", "c0:51:5c:c8:cb:73,40:e1:e4:bc:d8:30", "x"});
        }
        assertEquals("u1,\"c0:51:5c:c8:cb:73,40:e1:e4:bc:d8:30\",x",
                Files.readAllLines(file).get(1));
    }

    /**
     * Dates go out verbatim. {@code GenericCsvExporter} wraps date-shaped values in {@code ="…"} to
     * stop a spreadsheet reformatting them; in a data dump that wrapper is corruption.
     */
    @Test
    void datesAreNotWrappedInSpreadsheetFormulas() throws IOException {
        Path file = tempDir.resolve("out.csv");
        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, COLUMNS, 1024)) {
            writer.writeRow(new String[]{"2026-08-23 14:34:00", "2026-08-23", "x"});
        }
        assertEquals("2026-08-23 14:34:00,2026-08-23,x", Files.readAllLines(file).get(1));
    }

    @Test
    void rejectsRowsThatDoNotMatchTheColumnCount() throws IOException {
        Path file = tempDir.resolve("out.csv");
        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, COLUMNS, 1024)) {
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeRow(new String[]{"1", "2"}));
        }
    }
}
