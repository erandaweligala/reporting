package com.axonect.ee.enterpriseintegration.domain.service;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;

import java.nio.file.Path;

/**
 * A report that writes its own output as a single continuous stream instead of being pulled a page
 * at a time.
 *
 * <p>The paged {@link ReportDefinition} contract asks a downstream service for rows at an offset
 * and hands them back as a list of maps. That is the right shape for an operator browsing an audit
 * log, and the wrong one for a full-base extract: offset pagination re-walks everything it has
 * already skipped, so the cost of the last page grows with the size of the report, and each page
 * materialises its rows as maps before any of them reach the file. A definition that implements
 * this interface keeps the cursor open and writes rows as they arrive, so the report costs one
 * pass and a bounded amount of memory no matter how many rows it contains.
 */
public interface StreamingReportDefinition extends ReportDefinition {

    /**
     * Writes every row of the report to {@code outputPath}, which already holds the header line.
     *
     * @return the number of data rows written
     */
    long streamTo(DownloadReport report, Path outputPath) throws Exception;

    @Override
    default CommonAdaptorResp<FilterListViewBatch> fetchBatch(DownloadReport report, int offset, int limit) {
        throw new UnsupportedOperationException(
                reportType() + " is a streaming report and does not support paged fetching");
    }
}
