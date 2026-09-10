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
    void leavesTimestampsExactlyAsGivenWhenNoColumnIsNamedAsOne() throws IOException {
        // A writer given no timestamp columns carries the database's own text through untouched,
        // which is what a consumer reading the file with something other than a spreadsheet gets.
        Path file = tempDir.resolve("dates.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, 8192)) {
            writer.writeRow(new String[]{"14/08/2026 17:02:00", "2026-08-14 17:02:00"});
        }

        assertEquals("14/08/2026 17:02:00,2026-08-14 17:02:00", Files.readAllLines(file).get(0));
    }

    @Test
    void writesANamedTimestampColumnInTheFormASpreadsheetDisplays() throws IOException {
        // Excel converts a CSV timestamp to the day number behind it — 46271.07939 — and shows
        // that. Only the named column is rewritten: the one beside it holds the same text and has
        // to reach the file as it stands.
        Path file = tempDir.resolve("excel-safe.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(
                file, 8192, new boolean[]{false, true, false})) {
            writer.writeRow(new String[]{"2026-09-06 01:54:19", "2026-09-06 01:54:19", "plain"});
        }

        assertEquals("2026-09-06 01:54:19,=\"2026-09-06 01:54:19\",plain",
                Files.readAllLines(file).get(0));
    }

    @Test
    void writesTheFormulaUnquotedSoTheSpreadsheetStillSeesIt() throws IOException {
        // The rendered form carries a quote character, and the ordinary escape path would quote
        // the whole field and double it — which hands the spreadsheet a string rather than the
        // formula that keeps the timestamp readable.
        Path file = tempDir.resolve("unquoted.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, 8192, new boolean[]{true})) {
            writer.writeRow(new String[]{"2026-09-06 01:54:19.123"});
        }

        String content = Files.readString(file, StandardCharsets.UTF_8).strip();
        assertEquals("=\"2026-09-06 01:54:19\"", content);
    }

    @Test
    void anEmptyTimestampColumnStaysAnEmptyField() throws IOException {
        // A user with no bundle has no activation date, and an empty field is what the dump format
        // uses for it — not a formula around nothing.
        Path file = tempDir.resolve("empty-dates.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(
                file, 8192, new boolean[]{true, true, true})) {
            writer.writeRow(new String[]{null, "", "2026-09-06 01:54:19"});
        }

        assertEquals(",,=\"2026-09-06 01:54:19\"", Files.readAllLines(file).get(0));
    }

    @Test
    void aValueThatIsNotATimestampGoesDownTheOrdinaryPath() throws IOException {
        // The column is named as a timestamp, the value in it is not one. It is written and quoted
        // like any other value rather than being wrapped in a formula that would claim it is.
        Path file = tempDir.resolve("not-a-date.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, 8192, new boolean[]{true, true})) {
            writer.writeRow(new String[]{"aa:bb:cc,dd:ee:ff", "N/A"});
        }

        assertEquals("\"aa:bb:cc,dd:ee:ff\",N/A", Files.readAllLines(file).get(0));
    }

    @Test
    void headerLabelsAreNeverRewrittenByTheTimestampColumns() throws IOException {
        Path file = tempDir.resolve("header.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, 8192, new boolean[]{true, true})) {
            writer.writeHeader(new String[]{"CREATED_DATE", "UPDATED_DATE"});
            writer.writeRow(new String[]{"2026-09-06 01:54:19", null});
            assertEquals(1, writer.getRowCount());
        }

        assertEquals(List.of("CREATED_DATE,UPDATED_DATE", "=\"2026-09-06 01:54:19\","),
                Files.readAllLines(file));
    }

    @Test
    void aShorterFlagArrayLeavesTheColumnsItDoesNotCoverAlone() throws IOException {
        Path file = tempDir.resolve("short-flags.csv");

        try (StreamingCsvWriter writer = new StreamingCsvWriter(file, 8192, new boolean[]{true})) {
            writer.writeRow(new String[]{"2026-09-06 01:54:19", "2026-09-06 01:54:19"});
        }

        assertEquals("=\"2026-09-06 01:54:19\",2026-09-06 01:54:19", Files.readAllLines(file).get(0));
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
