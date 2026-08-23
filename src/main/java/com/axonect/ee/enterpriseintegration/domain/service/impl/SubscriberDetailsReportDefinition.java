package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.client.SubscriberDetailsAdaptor;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SubscriberDetailsReportDefinition implements ReportDefinition {

    @Autowired
    SubscriberDetailsAdaptor adaptor;

    @Override
    public String reportType() {
        return "SUBSCRIBER_DETAILS";
    }

    @Override
    public List<CsvColumn> columns() {
        return List.of(
                new CsvColumn("user_id", "User ID"),
                new CsvColumn("encryption_method", "Encryption Method"),
                new CsvColumn("user_name", "User Name"),
                new CsvColumn("nas_port_type", "NAS Port Type"),
                new CsvColumn("group_id", "Group ID"),
                new CsvColumn("bandwidth", "Bandwidth"),
                new CsvColumn("vlan_id", "VLAN ID"),
                new CsvColumn("circuit_id", "Circuit ID"),
                new CsvColumn("remote_id", "Remote ID"),
                new CsvColumn("mac_address", "MAC Address"),
                new CsvColumn("ip_allocation", "IP Allocation"),
                new CsvColumn("ip_pool_name", "IP Pool Name"),
                new CsvColumn("ipv4", "IPv4 Address"),
                new CsvColumn("ipv6", "IPv6 Address"),
                new CsvColumn("status", "Status"),
                new CsvColumn("contact_name", "Contact Name"),
                new CsvColumn("contact_email", "Contact Email"),
                new CsvColumn("contact_number", "Contact Number"),
                new CsvColumn("concurrency", "Concurrency"),
                new CsvColumn("billing", "Billing"),
                new CsvColumn("cycle_date", "Cycle Date"),
                new CsvColumn("billing_account_ref", "Billing Account Reference"),
                new CsvColumn("session_timeout", "Session Timeout"),
                new CsvColumn("idle_timeout", "Idle Timeout"),
                new CsvColumn("custom_timeout", "Custom Timeout"),
                new CsvColumn("request_id", "Request ID"),
                new CsvColumn("subscription", "Subscription"),
                new CsvColumn("created_timestamp", "Created Timestamp"),
                new CsvColumn("last_updated_timestamp", "Last Updated Timestamp")
        );
    }

    @Override
    public CommonAdaptorResp<FilterListViewBatch> fetchBatch(DownloadReport report, int offset, int limit) {
        return adaptor.fetchSubscriberDetails(report,offset,limit);
    }


}
