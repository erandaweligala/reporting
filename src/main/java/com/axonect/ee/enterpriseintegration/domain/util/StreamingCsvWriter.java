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
 * <p>Values are written exactly as the source produced them, with one exception the caller asks
 * for by name. A column named as a timestamp column is written through
 * {@link ExcelSafeTimestamp}, because a spreadsheet does not read a CSV timestamp as one — see
 * that class for what it does and why nothing else works. Every other column, and every column of
 * a writer given no timestamp columns at all, is quoted and written unchanged.
 */
public final class StreamingCsvWriter implements Closeable {

    private static final char DELIMITER = ',';
    private static final char QUOTE = '"';

    private final Writer out;
    private final String lineSeparator;
    private final boolean[] timestampColumns;
    private long rowCount;

    /**
     * @param path       file to append to; it is created when missing
     * @param bufferSize characters buffered before a write reaches the file system
     */
    public StreamingCsvWriter(Path path, int bufferSize) throws IOException {
        this(path, bufferSize, null);
    }

    /**
     * @param path             file to append to; it is created when missing
     * @param bufferSize       characters buffered before a write reaches the file system
     * @param timestampColumns one flag per column, true where the column carries a timestamp that
     *                         a spreadsheet must not convert; null or shorter than the row leaves
     *                         the columns it does not cover written as they are
     */
    public StreamingCsvWriter(Path path, int bufferSize, boolean[] timestampColumns) throws IOException {
        this.out = new BufferedWriter(
                new OutputStreamWriter(
                        Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                        StandardCharsets.UTF_8),
                Math.max(8192, bufferSize));
        this.lineSeparator = System.lineSeparator();
        this.timestampColumns = timestampColumns == null ? null : timestampColumns.clone();
    }

    /**
     * Writes the header line from the column labels, in the order given. A label is a name, never
     * a timestamp, so the header is written as it is whatever the timestamp columns are.
     */
    public void writeHeader(String[] labels) throws IOException {
        writeLine(labels, false);
        rowCount = 0;
    }

    /**
     * Writes one row. Null entries become empty fields, which is what the dump format uses for a
     * user with no bundle, no MAC address or no usage.
     */
    public void writeRow(String[] values) throws IOException {
        writeLine(values, true);
        rowCount++;
    }

    private void writeLine(String[] values, boolean data) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.write(DELIMITER);
            }
            if (data && isTimestampColumn(i)) {
                writeTimestamp(values[i]);
            } else {
                writeValue(values[i]);
            }
        }
        out.write(lineSeparator);
    }

    private boolean isTimestampColumn(int column) {
        return timestampColumns != null
                && column < timestampColumns.length
                && timestampColumns[column];
    }

    /**
     * Writes a timestamp column in the form {@link ExcelSafeTimestamp} produces. The rendered form
     * is written raw: it carries neither the delimiter nor a newline, and quoting it would hide
     * the formula from the spreadsheet, which is the whole reason for writing one. A value the
     * renderer does not recognise as a timestamp — an empty column, or a model this service did
     * not configure — goes down the ordinary path rather than being dressed up as something it is
     * not.
     */
    private void writeTimestamp(String value) throws IOException {
        String rendered = ExcelSafeTimestamp.render(value);
        if (rendered == null) {
            writeValue(value);
            return;
        }
        out.write(rendered);
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
