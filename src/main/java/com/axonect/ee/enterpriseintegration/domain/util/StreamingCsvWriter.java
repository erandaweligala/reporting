package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Writes one CSV file over a single file handle held open for the life of the report.
 *
 * <p>{@code GenericCsvExporter} appends each batch with {@code Files.writeString(APPEND)}, which
 * opens, writes and closes the file once per batch and builds the whole batch as a String first.
 * Across a multi-million-row dump that is tens of thousands of open/close cycles plus a large
 * short-lived String per batch. Here the stream is opened once, fields are written straight into a
 * large buffer, and nothing per-row is retained.
 *
 * <p>Values are written verbatim, quoted per RFC 4180 only when they contain a delimiter, quote or
 * newline. Note this deliberately does <em>not</em> apply the {@code ="…"} spreadsheet-formula
 * wrapper {@code GenericCsvExporter} puts around date-shaped values: this file is a data dump
 * consumed by downstream systems, and the wrapper would corrupt every date it touched.
 */
public final class StreamingCsvWriter implements Closeable {

    private static final char DELIMITER = ',';
    private static final char QUOTE = '"';
    /** RFC 4180 line ending, independent of the platform the report happens to run on. */
    private static final String LINE_END = "\r\n";

    private final Path path;
    private final Writer writer;
    private final int columnCount;
    private long rowsWritten;

    public StreamingCsvWriter(Path path, List<CsvColumn> columns, int bufferBytes) throws IOException {
        this.path = path;
        this.columnCount = columns.size();
        this.writer = new BufferedWriter(
                new OutputStreamWriter(
                        Files.newOutputStream(path,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE),
                        StandardCharsets.UTF_8),
                bufferBytes);

        String[] header = new String[columnCount];
        for (int i = 0; i < columnCount; i++) {
            header[i] = columns.get(i).getLabel();
        }
        writeFields(header);
    }

    /**
     * Append one row. {@code values} must be positionally aligned to the columns the writer was
     * created with; null entries are written as empty fields.
     */
    public void writeRow(String[] values) throws IOException {
        if (values.length != columnCount) {
            throw new IllegalArgumentException(
                    "Row has " + values.length + " values but the report has " + columnCount + " columns");
        }
        writeFields(values);
        rowsWritten++;
    }

    public long getRowsWritten() {
        return rowsWritten;
    }

    public Path getPath() {
        return path;
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    private void writeFields(String[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                writer.write(DELIMITER);
            }
            writeField(values[i]);
        }
        writer.write(LINE_END);
    }

    private void writeField(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!needsQuoting(value)) {
            writer.write(value);
            return;
        }
        writer.write(QUOTE);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == QUOTE) {
                writer.write(QUOTE);
            }
            writer.write(c);
        }
        writer.write(QUOTE);
    }

    /**
     * Single character scan; the equivalent check in {@code GenericCsvExporter} runs a regex per
     * cell, which at 39 columns x millions of rows is over a hundred million regex evaluations.
     */
    private static boolean needsQuoting(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == DELIMITER || c == QUOTE || c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }
}
