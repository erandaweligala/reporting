package com.axonect.ee.enterpriseintegration.domain.service;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;

import java.util.List;

public interface ReportDefinition {

    /**
     * Unique report type identifier
     * Example: ACTION_LOG, INVENTORY, USAGE, AUDIT
     */
    String reportType();

    /**
     * Column metadata used by CSV & Excel writers
     * Order matters
     */
    List<CsvColumn> columns();

    /**
     * Fetch one batch of data from the downstream service
     *
     * @param report DownloadReport metadata
     * @param offset pagination offset
     * @param limit  batch size
     * @return paginated response
     */
    CommonAdaptorResp<FilterListViewBatch> fetchBatch(
            DownloadReport report,
            int offset,
            int limit
    );
}

