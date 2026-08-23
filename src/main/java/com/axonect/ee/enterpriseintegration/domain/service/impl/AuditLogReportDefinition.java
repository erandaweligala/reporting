package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.client.AuditLogAdaptor;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AuditLogReportDefinition implements ReportDefinition {

    @Autowired
    AuditLogAdaptor adaptor;

    @Override
    public String reportType() {
        return "AUDIT_LOGS";
    }

    @Override
    public List<CsvColumn> columns() {
        return List.of(
                new CsvColumn("id", "ID"),
                new CsvColumn("activity", "Activity Name"),
                new CsvColumn("activityType", "Activity Type"),
                new CsvColumn("status", "Status"),
                new CsvColumn("description", "Details"),
                new CsvColumn("user", "Admin / System User"),
                new CsvColumn("createdDateTime", "Date and Time")
        );
    }

    @Override
    public CommonAdaptorResp<FilterListViewBatch> fetchBatch(
            DownloadReport report, int offset, int limit) {

        return adaptor.fetchAuditLogs(report, offset, limit);
    }
}
