package com.axonect.ee.enterpriseintegration.domain.constant;

/**
 * Rendering of database values as text inside the statement rather than in Java.
 *
 * <p>Every large extract in this service converts its timestamps with {@code TO_CHAR} and pulls
 * every column back with {@code getString}. At a few million rows the {@code Timestamp} and
 * {@code BigDecimal} objects a typed read would allocate — one per column per row — are what drives
 * the collector, not the I/O; converting in the database removes them from the pipeline entirely
 * and, as a side effect, makes the text in the file independent of the JVM's locale and time zone.
 */
public final class OracleText {

    /** The characters an Oracle format model may legitimately contain. */
    private static final String FORMAT_MODEL_CHARACTERS = "[A-Za-z0-9 :/.,\\-]+";

    private OracleText() {
    }

    /**
     * Renders one timestamp column as text under the given format model.
     *
     * <p>The value is CAST to TIMESTAMP first: the format models used here carry fractional
     * seconds, and TO_CHAR of a DATE with an FF element raises ORA-01821. The cast is free on a
     * column that is already a TIMESTAMP and makes the statement independent of which of the two
     * each column happens to be.
     */
    public static String timestampAsText(String column, String format) {
        return "TO_CHAR(CAST(" + column + " AS TIMESTAMP), '" + validateFormat(format) + "')";
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
