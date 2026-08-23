package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.client.SessionHistoryAdaptor;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SessionHistoryReportDefinition implements ReportDefinition {

    @Autowired
    SessionHistoryAdaptor adaptor;

    @Override
    public String reportType() {
        return "SESSION_HISTORY";
    }

    @Override
    public List<CsvColumn> columns() {
        return List.of(
                new CsvColumn("userName", "User Name"),
                new CsvColumn("groupId", "Group ID"),
                new CsvColumn("sessionId", "Session ID"),
                new CsvColumn("startTime", "Start Date"),
                new CsvColumn("endTime", "End Date"),
                new CsvColumn("connectionStatus", "Connection Status"),
                new CsvColumn("usage", "Usage")
        );
    }


    @Override
    public CommonAdaptorResp<FilterListViewBatch> fetchBatch(
            DownloadReport report, int offset, int limit) {

        return adaptor.fetchSessionHistory(report, offset, limit);
    }
}
