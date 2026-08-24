package com.axonect.ee.enterpriseintegration.domain.service;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;

import java.io.IOException;
import java.util.List;

/**
 * A report that is produced by streaming rather than by offset pagination.
 *
 * <p>{@link ReportDefinition} fetches page N by asking a downstream service to skip N rows, which
 * costs the source O(N) work per page and therefore O(N&sup2;) over a whole report. That is fine for
 * the log and catalogue reports it serves, which are read filtered and page-sized; it is not
 * viable for a multi-million-row dump. A streaming definition instead pushes every row exactly
 * once through a {@link RowSink} and never holds more than one source chunk in memory, so both
 * heap use and source cost stay linear in the number of rows.
 *
 * <p>Rows are passed as {@code String[]} positionally aligned to {@link #columns()} rather than as
 * maps: at millions of rows the per-row map would dominate allocation, and the column order is
 * already fixed by the report contract.
 */
public interface StreamingReportDefinition {

    /**
     * Unique report type identifier, registered in {@code ReportDefinitionsRegistry}.
     */
    String reportType();

    /**
     * Column metadata; order is the column order of the generated file.
     */
    List<CsvColumn> columns();

    /**
     * Push every row of this report into {@code sink}, in order.
     *
     * @return the number of rows emitted
     */
    long stream(DownloadReport report, RowSink sink) throws Exception;

    /**
     * Receives one row, positionally aligned to {@link #columns()}. A row array is only valid for
     * the duration of the call, so implementations may reuse the array between rows.
     */
    @FunctionalInterface
    interface RowSink {
        void write(String[] row) throws IOException;
    }
}
