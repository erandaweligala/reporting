package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.client.AuditLogAdaptor;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogReportDefinitionTest {

    @Mock
    private AuditLogAdaptor adaptor;

    @InjectMocks
    private AuditLogReportDefinition reportDefinition;

    // -------------------- reportType --------------------

    @Test
    void reportType_shouldReturnAuditLogs() {
        assertEquals("AUDIT_LOGS", reportDefinition.reportType());
    }

    // -------------------- columns --------------------

    @Test
    void columns_shouldReturnAllCsvColumns() {
        List<CsvColumn> columns = reportDefinition.columns();

        assertNotNull(columns);
        assertEquals(7, columns.size());

        assertEquals("id", columns.get(0).getKey());
        assertEquals("ID", columns.get(0).getLabel());

        assertEquals("activity", columns.get(1).getKey());
        assertEquals("Activity Name", columns.get(1).getLabel());

        assertEquals("activityType", columns.get(2).getKey());
        assertEquals("Activity Type", columns.get(2).getLabel());

        assertEquals("status", columns.get(3).getKey());
        assertEquals("Status", columns.get(3).getLabel());

        assertEquals("description", columns.get(4).getKey());
        assertEquals("Details", columns.get(4).getLabel());

        assertEquals("user", columns.get(5).getKey());
        assertEquals("Admin / System User", columns.get(5).getLabel());

        assertEquals("createdDateTime", columns.get(6).getKey());
        assertEquals("Date and Time", columns.get(6).getLabel());

    }

    // -------------------- fetchBatch --------------------

    @Test
    void fetchBatch_shouldDelegateToAdaptor() {
        DownloadReport report = new DownloadReport();
        int offset = 0;
        int limit = 10;

        CommonAdaptorResp<FilterListViewBatch> mockResponse =
                new CommonAdaptorResp<>();

        when(adaptor.fetchAuditLogs(report, offset, limit))
                .thenReturn(mockResponse);

        CommonAdaptorResp<FilterListViewBatch> response =
                reportDefinition.fetchBatch(report, offset, limit);

        assertNotNull(response);
        assertSame(mockResponse, response);

        verify(adaptor, times(1))
                .fetchAuditLogs(report, offset, limit);
    }
}

