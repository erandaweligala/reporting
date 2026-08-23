package com.axonect.ee.enterpriseintegration.domain.util;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A CSV sink that stays open for the whole report and appends one row at a time.
 *
 * <p>The batch exporter used by the paged reports rebuilds a String per batch and reopens the file
 * for every append. At a few thousand rows that is invisible; at 3 million it is the dominant cost
 * — every batch allocates a multi-megabyte String and pays a fresh open/close. This writer instead
 * holds a single buffered handle for the life of the dump and formats straight into it, so a row
 * costs one escape pass over its own characters and nothing else, and the file is flushed to disk
 * roughly once per buffer rather than once per batch.
 *
 * <p>Values are written exactly as the source produced them. The batch exporter rewrites anything
 * that looks like a date into an Excel formula so a spreadsheet will not reinterpret it; a data
 * dump consumed by another system needs the opposite, and quoting alone is applied here.
 */
public final class StreamingCsvWriter implements Closeable {

    private static final char DELIMITER = ',';
    private static final char QUOTE = '"';

    private final Writer out;
    private final String lineSeparator;
    private long rowCount;

    /**
     * @param path       file to append to; it is created when missing
     * @param bufferSize characters buffered before a write reaches the file system
     */
    public StreamingCsvWriter(Path path, int bufferSize) throws IOException {
        this.out = new BufferedWriter(
                new OutputStreamWriter(
                        Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                        StandardCharsets.UTF_8),
                Math.max(8192, bufferSize));
        this.lineSeparator = System.lineSeparator();
    }

    /** Writes the header line from the column labels, in the order given. */
    public void writeHeader(String[] labels) throws IOException {
        writeRow(labels);
        rowCount = 0;
    }

    /**
     * Writes one row. Null entries become empty fields, which is what the dump format uses for a
     * user with no bundle, no MAC address or no usage.
     */
    public void writeRow(String[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.write(DELIMITER);
            }
            writeValue(values[i]);
        }
        out.write(lineSeparator);
        rowCount++;
    }

    private void writeValue(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!needsQuoting(value)) {
            out.write(value);
            return;
        }
        out.write(QUOTE);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == QUOTE) {
                out.write(QUOTE);
            }
            out.write(c);
        }
        out.write(QUOTE);
    }

    private static boolean needsQuoting(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == DELIMITER || c == QUOTE || c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }

    /** Rows written since the header. */
    public long getRowCount() {
        return rowCount;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
