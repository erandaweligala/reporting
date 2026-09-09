package com.axonect.ee.enterpriseintegration.domain.constant;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the single statement behind a table extract — a report that is one CSV row per row of a
 * database table, with the column names the consuming system uses rather than the ones the schema
 * uses.
 *
 * <p>The statement is deliberately as plain as a statement can be: a projection over a
 * {@link Spec#from() from clause}, with no ORDER BY, no window function and no aggregation. That is
 * not laziness, it is the point. An extract of this kind is read end to end, so the cheapest plan
 * Oracle has for it is a single multi-block full scan, and every clause that could be added takes
 * that away:
 *
 * <ul>
 *   <li>An ORDER BY over a few million rows either sorts in temp space or walks the primary key
 *       index and picks rows up one block at a time — random I/O where the scan was sequential.
 *       The consuming system loads the file into a table, where row order means nothing.</li>
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
     * @param source    qualified column, or any expression over the from clause
     * @param timestamp whether the source is a date/timestamp that must be rendered under the
     *                  extract's format model rather than the session's NLS settings
     */
    public record Column(String label, String source, boolean timestamp) {

        /** A column taken as it is: text, a number, or an expression that already yields one. */
        public static Column plain(String label, String source) {
            return new Column(label, source, false);
        }

        /** A date or timestamp column, rendered with the extract's own format model. */
        public static Column at(String label, String source) {
            return new Column(label, source, true);
        }
    }

    /**
     * What one extract selects.
     *
     * @param reportType the report type it is registered and requested under
     * @param from       the from clause, table alias included
     * @param columns    the extract's columns, in the order the consuming system expects them
     */
    public record Spec(String reportType, String from, List<Column> columns) {

        public Spec {
            columns = List.copyOf(columns);
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
        String selectList = spec.columns().stream()
                .map(column -> expressionOf(column, dateFormat))
                .collect(Collectors.joining(", "));

        return new SqlStatement("SELECT " + selectList + " FROM " + spec.from(), List.of());
    }

    private static String expressionOf(Column column, String dateFormat) {
        return column.timestamp()
                ? OracleText.timestampAsText(column.source(), dateFormat)
                : column.source();
    }
}
