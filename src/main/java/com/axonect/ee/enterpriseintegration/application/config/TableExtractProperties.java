package com.axonect.ee.enterpriseintegration.application.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the table extracts (MAC_SERVICE_TABLE, PLAN_TO_BUCKET, BUCKET_INSTANCE).
 *
 * <p>The defaults are sized for the largest of them — a few million rows — where the fetch size is
 * what removes the Oracle round trips from the cost of the scan and the buffer is what keeps the
 * writer down to roughly one write syscall per thousand rows. They are safe for the small one too:
 * a buffer is allocated per running extract, not per row.
 */
@Component
@ConfigurationProperties(prefix = "report.table-extract")
@Data
public class TableExtractProperties {

    /** Rows Oracle ships per round trip. Every column is text, so a large fetch stays cheap. */
    private int jdbcFetchSize = 5000;

    /**
     * Seconds an extract may run before Oracle cancels it. It bounds a scan that has gone wrong —
     * a plan that has flipped, a table that has grown past what the window allows — rather than
     * letting it hold a cursor and a connection indefinitely.
     */
    private int queryTimeoutSeconds = 7200;

    /** Character buffer, in bytes, held in front of the CSV file. */
    private int csvBufferBytes = 1 << 20;

    /**
     * Oracle format model applied to every date and timestamp column of every extract. It is what
     * makes the text in the file independent of the JVM's and the session's NLS settings.
     *
     * <p>The milliseconds the sample extracts carried are no longer part of it. The three files
     * are read in a spreadsheet before they are loaded anywhere, the reported format is
     * {@code yyyy-MM-dd HH:mm:ss}, and {@link #excelSafeTimestamps} normalises a fractional
     * seconds field away in any case — so a model that still asked for one would change nothing an
     * operator sees and would only put the two sides of that switch out of step with each other.
     */
    private String dateFormat = "YYYY-MM-DD HH24:MI:SS";

    /**
     * Whether the timestamp columns of every extract are written in the form a spreadsheet
     * displays rather than converts — {@code ="2026-08-18 14:31:23"} instead of
     * {@code 2026-08-18 14:31:23}.
     *
     * <p>On, because an extract is opened in Excel before it is loaded, and Excel does not read a
     * CSV timestamp as a timestamp: it converts the text to the day number behind it and shows
     * that number ({@code 46271.07939}), which is what every date column of these three files was
     * reading. There is no format model that avoids it — the {@code ="..."} form is a formula
     * whose value is the string, so it is displayed exactly as written — and it is what the user
     * data dump and the batch exporter behind the paged reports already do.
     *
     * <p>Off is for a consumer that loads an extract with something other than a spreadsheet and
     * wants the database's own text back: the {@code ="..."} is a spreadsheet formula, and every
     * other reader sees the six characters around the timestamp. Nothing else about the file
     * changes with it.
     *
     * @see com.axonect.ee.enterpriseintegration.domain.util.ExcelSafeTimestamp
     */
    private boolean excelSafeTimestamps = true;
}
