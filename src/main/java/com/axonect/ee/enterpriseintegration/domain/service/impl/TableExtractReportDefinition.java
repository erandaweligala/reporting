package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.config.TableExtractProperties;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.constant.SqlStatement;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Column;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.repository.StreamingRowReader;
import com.axonect.ee.enterpriseintegration.domain.repository.StreamingRowReader.RowHandler;
import com.axonect.ee.enterpriseintegration.domain.service.ExtractColumnSource;
import com.axonect.ee.enterpriseintegration.domain.service.StreamingReportDefinition;
import com.axonect.ee.enterpriseintegration.domain.util.StreamingCsvWriter;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * A whole table, as CSV. One instance serves one {@link Spec}; the three extracts differ only in
 * what they select, so they share this class rather than each repeating the loop.
 *
 * <p>What keeps a few million rows from costing the service anything it cannot afford:
 *
 * <ul>
 *   <li><b>One cursor, not pages.</b> Rows arrive on an open, read-only, forward-only JDBC cursor
 *       and are written as they arrive. The paged report path asks for rows at an ever-growing
 *       offset, which re-walks and discards everything before each page — the last page of a three
 *       million row extract costs three million rows of work on its own.</li>
 *   <li><b>Bounded memory, whatever the row count.</b> Nothing is accumulated: one reusable row
 *       buffer, one buffered writer, and a row that never outlives the callback that writes it. A
 *       ten thousand row extract and a ten million row extract have the same heap profile, so an
 *       extract cannot be the reason the service pauses for a full collection.</li>
 *   <li><b>One sequential pass over the table.</b> The statement has no shard predicate and, unless
 *       something outside the database is being read in step with it, no ORDER BY — so Oracle reads
 *       the table the cheapest way it can and reads it exactly once. See {@link TableExtractSql}
 *       for why parallel shards would cost the database more and finish no sooner.</li>
 *   <li><b>Text conversion in the database.</b> Every column comes back with {@code getString}, so
 *       no {@code Timestamp} or {@code BigDecimal} is allocated per column per row.</li>
 * </ul>
 *
 * <p>A column the spec declares {@link Column#spliced} is not read from the cursor at all: the
 * statement selects nothing for it and an {@link ExtractColumnSource} fills it once the rest of the
 * row is in the buffer. BUCKET_INSTANCE.USAGE is that column when it reports the CDR total out of
 * Elasticsearch, and the source is then what holds the aggregation cursor for the run — see
 * {@link BucketUsageColumnSource}. An extract with no such column is handed
 * {@link ExtractColumnSource#NONE} and behaves exactly as it did before the concept existed.
 *
 * <p>The columns the spec renders with TO_CHAR are named to the writer, which writes them in the
 * form a spreadsheet displays rather than converts; see {@link #timestampColumns()} and
 * {@code report.table-extract.excel-safe-timestamps}.
 *
 * <p>The extract runs on the report executor like any other report, so at most
 * {@code report.max-concurrent} of them can be in flight and the rest queue as Pending. That, and
 * a single connection held for the length of one extract, is the whole footprint on the database.
 */
@Slf4j
public class TableExtractReportDefinition implements StreamingReportDefinition {

    private final Spec spec;
    private final StreamingRowReader rowReader;
    private final TableExtractProperties properties;
    private final Supplier<ExtractColumnSource> columnSource;
    private final List<CsvColumn> columns;

    /**
     * Result set position of each CSV column, 1-based, or 0 where the statement selects nothing for
     * it. A spliced column shifts every column after it, so the mapping is derived from the spec
     * rather than assumed to be the identity it is for an extract that reads only the database.
     */
    private final int[] resultColumns;

    public TableExtractReportDefinition(Spec spec,
                                        StreamingRowReader rowReader,
                                        TableExtractProperties properties) {
        this(spec, rowReader, properties, () -> ExtractColumnSource.NONE);
    }

    /**
     * @param columnSource opens the source of this extract's spliced columns, once per run. It is
     *                     a supplier rather than a source because a source may hold a cursor of its
     *                     own, and a cursor belongs to one run
     */
    public TableExtractReportDefinition(Spec spec,
                                        StreamingRowReader rowReader,
                                        TableExtractProperties properties,
                                        Supplier<ExtractColumnSource> columnSource) {
        this.spec = spec;
        this.rowReader = rowReader;
        this.properties = properties;
        this.columnSource = columnSource;
        this.columns = spec.columns().stream()
                .map(column -> new CsvColumn(column.label().toLowerCase(Locale.ROOT), column.label()))
                .toList();
        this.resultColumns = resultColumns(spec);
    }

    @Override
    public String reportType() {
        return spec.reportType();
    }

    @Override
    public List<CsvColumn> columns() {
        return columns;
    }

    @Override
    public long streamTo(DownloadReport report, Path outputPath) throws Exception {
        SqlStatement statement = TableExtractSql.build(spec, properties.getDateFormat());
        long start = System.currentTimeMillis();

        // One row buffer for the whole extract. The writer copies out of it before the next row is
        // read, so it is safe to reuse and saves an array per row.
        String[] row = new String[columns.size()];

        try (ExtractColumnSource source = columnSource.get();
             StreamingCsvWriter writer = new StreamingCsvWriter(
                     outputPath, properties.getCsvBufferBytes(), timestampColumns())) {

            long rows = stream(statement, resultSet -> writeRow(resultSet, row, writer, source));

            log.info("Report ID: {} | {} wrote {} rows in {} ms",
                    report.getId(), spec.reportType(), rows, System.currentTimeMillis() - start);
            return rows;
        }
    }

    /**
     * Opens the cursor, in binary collation where the extract carries an ordering.
     *
     * <p>Binary collation costs a round trip and exists for a merge join: an ordering produced
     * outside the database has to be the one the database produces, and a session that happened to
     * default to a linguistic sort would silently mismatch rows. An extract that reads a table end
     * to end has no ordering to agree with and must not pay for it.
     */
    private long stream(SqlStatement statement, RowHandler handler) throws Exception {
        int fetchSize = properties.getJdbcFetchSize();
        int timeout = properties.getQueryTimeoutSeconds();

        return spec.ordered()
                ? rowReader.streamInBinaryOrder(statement, fetchSize, timeout, handler)
                : rowReader.stream(statement, fetchSize, timeout, handler);
    }

    /**
     * The flag per column the writer needs: true where the extract renders the column with TO_CHAR,
     * so the value reaches the file as {@code ="2026-08-18 14:31:23"} and an operator opening the
     * extract reads the timestamp rather than the day number Excel would otherwise convert it to.
     * Null when the extracts are configured to write the database's own text, which leaves the
     * writer behaving exactly as it did before any column was named to it.
     *
     * <p>The flags are derived from the spec rather than from a list of column names kept beside
     * it. {@link TableExtractSql.Column#at} is already the declaration that a column is a
     * timestamp — it is what puts the column inside the TO_CHAR — so a column added to an extract
     * cannot be rendered as a date by the statement and written as raw text by the writer.
     */
    private boolean[] timestampColumns() {
        if (!properties.isExcelSafeTimestamps()) {
            return null;
        }
        boolean[] flags = new boolean[spec.columns().size()];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = spec.columns().get(i).timestamp();
        }
        return flags;
    }

    /** Where each CSV column sits in the result set; 0 for one the statement does not select. */
    private static int[] resultColumns(Spec spec) {
        int[] positions = new int[spec.columns().size()];
        int selected = 0;
        for (int i = 0; i < positions.length; i++) {
            positions[i] = spec.columns().get(i).selected() ? ++selected : 0;
        }
        return positions;
    }

    private void writeRow(ResultSet resultSet, String[] row, StreamingCsvWriter writer,
                          ExtractColumnSource source) throws Exception {
        for (int column = 0; column < row.length; column++) {
            row[column] = resultColumns[column] == 0 ? null : resultSet.getString(resultColumns[column]);
        }
        source.fill(resultSet, row);
        writer.writeRow(row);
    }
}
