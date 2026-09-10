package com.axonect.ee.enterpriseintegration.domain.constant;

/**
 * Rendering of database values as text inside the statement rather than in Java.
 *
 * <p>Every large extract in this service converts its timestamps with {@code TO_CHAR} and pulls
 * every column back with {@code getString}. At a few million rows the {@code Timestamp} and
 * {@code BigDecimal} objects a typed read would allocate — one per column per row — are what drives
 * the collector, not the I/O; converting in the database removes them from the pipeline entirely
 * and, as a side effect, makes the text in the file independent of the JVM's locale and time zone.
 *
 * <p>Both halves of what goes into the rendered expression — the column and the format model — are
 * checked here before they reach the SQL. Nothing in a report statement is written out by hand, so
 * a malformed fragment is not caught by reading the statement: it is caught by the database, at the
 * end of the run that assembled it, as an ORA number against a statement no one has ever seen. The
 * checks turn that into an {@link IllegalArgumentException} naming the fragment, thrown before a
 * cursor is opened.
 */
public final class OracleText {

    /** The characters an Oracle format model may legitimately contain. */
    private static final String FORMAT_MODEL_CHARACTERS = "[A-Za-z0-9 :/.,\\-]+";

    /** An unquoted Oracle identifier: a column, a table alias, or an alias given to a column. */
    private static final String IDENTIFIER = "[A-Za-z][A-Za-z0-9_$#]*";

    /** A column reference, optionally qualified by its table alias. Nothing else, and no alias. */
    private static final String COLUMN_REFERENCE = IDENTIFIER + "(\\." + IDENTIFIER + ")?";

    private OracleText() {
    }

    /**
     * Renders one timestamp column as text under the given format model.
     *
     * <p>The value is CAST to TIMESTAMP first: a format model may carry fractional seconds — the
     * table extracts' model does — and TO_CHAR of a DATE with an FF element raises ORA-01821. The
     * cast is free on a column that is already a TIMESTAMP, and it makes the statement independent
     * both of which of the two each column happens to be and of whether the configured model asks
     * for a fraction, so a model can gain or lose its FF element without the expression changing
     * shape around it.
     */
    public static String timestampAsText(String column, String format) {
        return timestampAsText(column, format, null);
    }

    /**
     * Renders one timestamp column as text and names the result.
     *
     * <p>The alias is appended to the finished expression rather than passed in with the column,
     * and that is the whole reason this overload exists. {@code column} is interpolated into the
     * CAST, so a caller naming the column the way a select list normally does — {@code
     * "u.CREATED_DATE AS CUSTOMER_ACTIVATION_DATE"} — produces {@code CAST(u.CREATED_DATE AS
     * CUSTOMER_ACTIVATION_DATE AS TIMESTAMP)}: a second AS where the cast's closing bracket
     * belongs, which Oracle rejects with ORA-00907 (missing right parenthesis). It rejects the
     * whole statement, not the column, so the entire dump fails on every shard of every run. That
     * is what {@link #validateColumn} is here to stop, and this is the supported way to do what the
     * caller was reaching for.
     *
     * @param column a column reference, optionally qualified — not an expression, and not an alias
     * @param format the Oracle format model the value is rendered under
     * @param alias  the name the rendered column is given, or null to leave it unnamed
     */
    public static String timestampAsText(String column, String format, String alias) {
        String text = "TO_CHAR(CAST(" + validateColumn(column) + " AS TIMESTAMP), '"
                + validateFormat(format) + "')";
        return alias == null ? text : text + " AS " + validateAlias(alias);
    }

    /**
     * The column goes inside a CAST, so anything the cast's grammar does not expect there ends the
     * statement rather than the column — see {@link #timestampAsText(String, String, String)}.
     * Restricting it to a plain column reference keeps an alias, an expression and a quoted
     * identifier out of that position, and each of those has somewhere better to be: the alias
     * belongs to the overload above.
     */
    public static String validateColumn(String column) {
        if (column == null || !column.matches(COLUMN_REFERENCE)) {
            throw new IllegalArgumentException(
                    "A timestamp column must be a plain column reference, optionally qualified, "
                            + "and must not carry an alias; pass the alias separately: " + column);
        }
        return column;
    }

    /** The name given to a rendered column, checked the same way the column itself is. */
    public static String validateAlias(String alias) {
        if (alias == null || !alias.matches(IDENTIFIER)) {
            throw new IllegalArgumentException("Unsupported column alias: " + alias);
        }
        return alias;
    }

    /**
     * The format model is concatenated into the statement — Oracle will not fold a bind variable
     * into a format model without re-parsing it per row — so it is restricted to the characters a
     * format model can legitimately contain before it goes anywhere near the SQL.
     */
    public static String validateFormat(String format) {
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("An Oracle date format model must be configured");
        }
        if (!format.matches(FORMAT_MODEL_CHARACTERS)) {
            throw new IllegalArgumentException("Unsupported Oracle date format model: " + format);
        }
        return format;
    }
}
