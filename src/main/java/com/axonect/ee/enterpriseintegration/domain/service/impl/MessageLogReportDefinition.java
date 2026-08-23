package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.client.MessageLogAdaptor;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MessageLogReportDefinition implements ReportDefinition {

    @Autowired
    MessageLogAdaptor adaptor;

    @Override
    public String reportType() {
        return "MESSAGE_LOGS";
    }

    @Override
    public List<CsvColumn> columns() {
        return List.of(
                new CsvColumn("id", "ID"),
                new CsvColumn("adminUser", "Admin User"),
                new CsvColumn("userName", "User Name"),
                new CsvColumn("groupId", "Group ID"),
                new CsvColumn("dateTime", "Date Time"),
                new CsvColumn("requestId", "Request ID"),
                new CsvColumn("action", "Action"),
                new CsvColumn("resultCode", "Result Code"),
                new CsvColumn("httpStatus", "HTTP Status"),
                new CsvColumn("description", "Description"),
                new CsvColumn("channel", "Channel"),
                new CsvColumn("application", "Application"),
                new CsvColumn("opco", "Opco"),
                new CsvColumn("responseTime", "Response Time"),
                new CsvColumn("requestStartTime", "Request Start Time"),
                new CsvColumn("requestEndTime", "Request End Time")

        );
    }


    @Override
    public CommonAdaptorResp<FilterListViewBatch> fetchBatch(
            DownloadReport report, int offset, int limit) {

        return adaptor.fetchActionLogs(report, offset, limit);
    }
}
