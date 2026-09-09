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
     * Oracle format model applied to every date and timestamp column of every extract. It matches
     * the sample extracts, milliseconds included, and is what makes the file independent of the
     * JVM's and the session's NLS settings.
     */
    private String dateFormat = "YYYY-MM-DD HH24:MI:SS.FF3";
}
