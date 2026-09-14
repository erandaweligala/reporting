package com.axonect.ee.enterpriseintegration.domain.constant;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the single statement behind a table extract — a report that is one CSV row per row of a
 * database table, with the column names the consuming system uses rather than the ones the schema
 * uses.
 *
 * <p>The statement is deliberately as plain as a statement can be: a projection over a
 * {@link Spec#from() from clause}, with no GROUP BY, no window function and — unless the extract
 * has an ordering to keep, see below — no ORDER BY. That is not laziness, it is the point. An
 * extract of this kind is read end to end, so the cheapest plan Oracle has for it is a single
 * multi-block full scan, and every clause that could be added takes that away:
 *
 * <ul>
 *   <li>An ORDER BY over a few million rows either sorts in temp space or walks the primary key
 *       index and picks rows up one block at a time — random I/O where the scan was sequential.
 *       The consuming system loads the file into a table, where row order means nothing, so an
 *       extract pays for one only where something outside the database is being read in step with
 *       it: {@link Spec#orderBy()} is that case and nothing else.</li>
 *   <li>A shard predicate on the key would let several cursors run at once, which is what the user
 *       dump does — but there each shard also had an Elasticsearch aggregation to overlap with and
 *       a four-way join to plan. Here there is nothing to overlap: N shards over one flat table
 *       are N scans of it, and the database does the same work N times to finish no sooner.</li>
 * </ul>
 *
 * <p>Timestamps are converted to text by Oracle so the reader can pull every column with
 * {@code getString}; see {@link OracleText}.
 */
public final class TableExtractSql {

    private TableExtractSql() {
    }

    /**
     * One column of the extract: the header the consumer expects, and the expression that produces
     * it.
     *
     * @param label     header written to the CSV, and the name the consuming system knows the
     *                  column by — it is not always the name of the underlying schema column
     * @param source    qualified column, or — for a plain column — any expression over the from
     *                  clause. A timestamp source is CAST before it is rendered, so it has to be a
     *                  column reference and nothing else; see {@link OracleText#validateColumn}.
     *                  Null for a {@link #spliced} column, which the statement does not select at
     *                  all
     * @param timestamp whether the source is a date/timestamp that must be rendered under the
     *                  extract's format model rather than the session's NLS settings
     */
    public record Column(String label, String source, boolean timestamp) {

        /** A column taken as it is: text, a number, or an expression that already yields one. */
        public static Column plain(String label, String source) {
            return new Column(label, source, false);
        }

        /**
         * A date or timestamp column, rendered with the extract's own format model. The source
         * goes inside a CAST, so it is a column reference — an alias or an expression there ends
         * the statement rather than the column.
         */
        public static Column at(String label, String source) {
            return new Column(label, source, true);
        }

        /**
         * A column the statement selects nothing for, because the database is not where its value
         * comes from. It keeps its place in the header and in the row, and the value is spliced in
         * after the row is read — which is what BUCKET_INSTANCE.USAGE is, once it reports the CDR
         * total from Elasticsearch rather than the table's own counter.
         *
         * <p>This is the same shape the user data dump gives NAS_IP_ADDRESS: a column of the file
         * that no column of the schema produces, left out of the select list rather than selected
         * and thrown away, so the statement says plainly what it does and does not read.
         */
        public static Column spliced(String label) {
            return new Column(label, null, false);
        }

        /** Whether the statement selects an expression for this column. */
        public boolean selected() {
            return source != null;
        }
    }

    /**
     * What one extract selects.
     *
     * @param reportType the report type it is registered and requested under
     * @param from       the from clause, table alias included
     * @param columns    the extract's columns, in the order the consuming system expects them
     * @param helpers    columns selected after them that are not written to the CSV at all. They
     *                   are what a {@link Column#spliced} column is filled from — the username a
     *                   bucket's usage is keyed on in the CDR documents, say — so the row carries
     *                   them without the file doing so
     * @param orderBy    ordering the statement must produce, or null for none. An extract only has
     *                   one where it is read in step with something outside the database, which is
     *                   also why the cursor is opened in binary collation when it is set: the
     *                   ordering has to be the one the other side produces, not the session's
     *                   linguistic sort
     */
    public record Spec(String reportType, String from, List<Column> columns, List<Column> helpers,
                       String orderBy) {

        public Spec {
            columns = List.copyOf(columns);
            helpers = List.copyOf(helpers);
        }

        /** An extract that is a plain projection: every column selected, nothing ordered. */
        public Spec(String reportType, String from, List<Column> columns) {
            this(reportType, from, columns, List.of(), null);
        }

        /** Whether the statement carries an ordering that has to agree with one outside it. */
        public boolean ordered() {
            return orderBy != null;
        }

        /**
         * Where {@code label} lands in the result set, 1-based — for a column of the extract or
         * for one of the {@link #helpers}, which are selected after them.
         *
         * <p>Positions are derived from the spec rather than written down beside it, because a
         * spliced column shifts every column after it: the statement selects one expression fewer
         * than the CSV has columns, and a number kept by hand would go quietly wrong the next time
         * one is added.
         */
        public int resultIndex(String label) {
            int index = 0;
            for (Column column : columns) {
                if (!column.selected()) {
                    if (column.label().equals(label)) {
                        throw new IllegalArgumentException(
                                reportType + "." + label + " is spliced in after the row is read, "
                                        + "so the result set has no column for it");
                    }
                    continue;
                }
                index++;
                if (column.label().equals(label)) {
                    return index;
                }
            }
            for (Column helper : helpers) {
                index++;
                if (helper.label().equals(label)) {
                    return index;
                }
            }
            throw new IllegalArgumentException(reportType + " selects no column named " + label);
        }

        /** Where {@code label} lands in the CSV row, 0-based. */
        public int csvIndex(String label) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).label().equals(label)) {
                    return i;
                }
            }
            throw new IllegalArgumentException(reportType + " has no column named " + label);
        }
    }

    /**
     * Builds the extract statement.
     *
     * <p>It carries no parameters: an extract has no filters, and the one value that varies with
     * configuration — the date format model — cannot be bound, so it is validated and concatenated
     * (see {@link OracleText#validateFormat}). Everything else in the statement comes from a
     * {@link Spec} compiled into this service, never from a request.
     */
    public static SqlStatement build(Spec spec, String dateFormat) {
        String selectList = Stream.concat(
                        spec.columns().stream().filter(Column::selected),
                        spec.helpers().stream())
                .map(column -> expressionOf(column, dateFormat))
                .collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(selectList)
                .append(" FROM ")
                .append(spec.from());
        if (spec.ordered()) {
            sql.append(" ORDER BY ").append(spec.orderBy());
        }

        return new SqlStatement(sql.toString(), List.of());
    }

    private static String expressionOf(Column column, String dateFormat) {
        return column.timestamp()
                ? OracleText.timestampAsText(column.source(), dateFormat)
                : column.source();
    }
}
