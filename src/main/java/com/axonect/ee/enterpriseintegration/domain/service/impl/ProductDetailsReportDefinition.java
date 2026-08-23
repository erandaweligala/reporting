package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.client.ProductDetailsAdaptor;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ProductDetailsReportDefinition implements ReportDefinition {

    @Autowired
    ProductDetailsAdaptor adaptor;

    @Override
    public String reportType() {
        return "PRODUCT_DETAILS";
    }

    @Override
    public List<CsvColumn> columns() {
        return List.of(
                new CsvColumn("planInternalId", "Plan Internal ID"),
                new CsvColumn("planId", "Plan ID"),
                new CsvColumn("planName", "Plan Name"),
                new CsvColumn("planType", "Plan Type"),
                new CsvColumn("recurringFlag", "Recurring Flag"),
                new CsvColumn("recurringPeriod", "Recurring Period"),
                new CsvColumn("status", "Status"),
                new CsvColumn("connectionType", "Connection Type"),
                new CsvColumn("quotaProrationFlag", "Quota Proration Flag"),
                new CsvColumn("createdAt", "Created At"),
                new CsvColumn("updatedAt", "Updated At"),
                new CsvColumn("bucketList", "Bucket List")
        );
    }


    @Override
    public CommonAdaptorResp<FilterListViewBatch> fetchBatch(
            DownloadReport report, int offset, int limit) {

        return adaptor.fetchProductDetails(report, offset, limit);
    }
}
