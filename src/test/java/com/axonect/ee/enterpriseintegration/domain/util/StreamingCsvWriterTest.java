package com.axonect.ee.enterpriseintegration.domain.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingCsvWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesHeaderAndRowsInOrder() throws IOException {
        Path file = tempDir.resolve("dump.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, 8192)) {
            writer.writeHeader(new String[]{"A", "B", "C"});
            writer.writeRow(new String[]{"1", "2", "3"});
            writer.writeRow(new String[]{"4", "5", "6"});
            assertEquals(2, writer.getRowCount());
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(List.of("A,B,C", "1,2,3", "4,5,6"), lines);
    }

    @Test
    void quotesOnlyValuesThatNeedIt() throws IOException {
        Path file = tempDir.resolve("quoting.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, 8192)) {
            writer.writeRow(new String[]{
                    "aa:bb:cc,dd:ee:ff",
                    "say \"hello\"",
                    "line\nbreak",
                    "plain"});
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\"aa:bb:cc,dd:ee:ff\",\"say \"\"hello\"\"\",\"line\nbreak\",plain"),
                "unexpected content: " + content);
    }

    @Test
    void nullsBecomeEmptyFields() throws IOException {
        Path file = tempDir.resolve("nulls.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, 8192)) {
            writer.writeRow(new String[]{null, "x", null});
        }

        assertEquals(",x,", Files.readAllLines(file).get(0));
    }

    @Test
    void leavesTimestampsExactlyAsGiven() throws IOException {
        // The batch exporter rewrites date-looking values into an Excel formula. A dump consumed
        // by another system must carry the database's own text through untouched.
        Path file = tempDir.resolve("dates.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, 8192)) {
            writer.writeRow(new String[]{"14/08/2026 17:02:00", "2026-08-14 17:02:00"});
        }

        assertEquals("14/08/2026 17:02:00,2026-08-14 17:02:00", Files.readAllLines(file).get(0));
    }

    @Test
    void appendsToAnExistingFile() throws IOException {
        Path file = tempDir.resolve("append.csv");
        Files.writeString(file, "HEADER" + System.lineSeparator());

        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, 8192)) {
            writer.writeRow(new String[]{"row"});
        }

        assertEquals(List.of("HEADER", "row"), Files.readAllLines(file));
    }
}
