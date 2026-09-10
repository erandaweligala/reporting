package com.axonect.ee.enterpriseintegration.domain.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders one timestamp so that Excel <em>displays</em> it instead of reinterpreting it.
 *
 * <p>A CSV field carries no type, so Excel guesses one for every cell it opens, and a timestamp is
 * the guess it gets wrong: it recognises the text as a date, replaces it with the day number
 * behind it — {@code 46271.07939} — and does not always put a date format on the cell it just
 * converted. What the operator then reads is the serial. The same guess is what turns an SLMN into
 * {@code 5.1E+09}; a date column is only where it is impossible to ignore.
 *
 * <p>The way out is not a better format model, because there is no text shape that reads as
 * {@code yyyy-MM-dd HH:mm:ss} and is also left alone. It is to stop the guess: a field that begins
 * with {@code =} is a formula, and {@code ="2026-09-06 01:54:19"} is the formula whose value is
 * that string. Excel shows it exactly as written, keeps it as text through a copy or a save, and
 * never converts anything. This is the convention the batch exporter behind the paged reports has
 * always used ({@code GenericCsvExporter}); the streaming reports are what did not have it.
 *
 * <p>The rendered text is normalised to {@code yyyy-MM-dd HH:mm:ss} on the way through — a
 * fractional seconds field is dropped, and a {@code T} separator becomes a space. That is what
 * makes the displayed format a property of the file rather than of whichever Oracle model the
 * deployment happens to have configured: a {@code date-format} still carrying {@code FF3} produces
 * the same cell as one that does not.
 */
public final class ExcelSafeTimestamp {

    /**
     * The shapes Oracle produces under a {@code YYYY-MM-DD HH24:MI:SS} model, with or without a
     * fractional seconds element, plus the date-only model of a column that has no time in it.
     * Anything else — a differently ordered model, a number, a name — is not a timestamp as far as
     * this class is concerned and is left for the caller to write unchanged.
     */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})(?:[ T](\\d{2}:\\d{2}:\\d{2})(?:[.,]\\d+)?)?");

    private ExcelSafeTimestamp() {
    }

    /**
     * @param value a rendered timestamp, or any other column value
     * @return the {@code ="yyyy-MM-dd HH:mm:ss"} form Excel displays verbatim, or {@code null} when
     *         the value is not a timestamp this class recognises and has to be written as it is
     */
    public static String render(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = TIMESTAMP.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        String time = matcher.group(2);
        return time == null
                ? "=\"" + matcher.group(1) + "\""
                : "=\"" + matcher.group(1) + " " + time + "\"";
    }
}
