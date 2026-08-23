package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.client.SessionHistoryAdaptor;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionHistoryReportDefinitionTest {

    @Mock
    private SessionHistoryAdaptor adaptor;

    private SessionHistoryReportDefinition reportDefinition;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reportDefinition = new SessionHistoryReportDefinition();
        reportDefinition.adaptor = adaptor; // manually inject mock
    }

    @Test
    void reportType_ShouldReturnSessionHistory() {
        String type = reportDefinition.reportType();
        assertEquals("SESSION_HISTORY", type);
    }

    @Test
    void columns_ShouldReturnAllCsvColumns() {
        List<CsvColumn> columns = reportDefinition.columns();
        assertNotNull(columns);
        assertEquals(7, columns.size());
        assertEquals("userName", columns.get(0).getKey());
    }

    @Test
    void fetchBatch_ShouldDelegateToAdaptor() {
        DownloadReport report = new DownloadReport();
        int offset = 0;
        int limit = 10;

        CommonAdaptorResp<FilterListViewBatch> mockResp = new CommonAdaptorResp<>();
        when(adaptor.fetchSessionHistory(report, offset, limit)).thenReturn(mockResp);

        CommonAdaptorResp<FilterListViewBatch> result = reportDefinition.fetchBatch(report, offset, limit);

        assertNotNull(result);
        verify(adaptor, times(1)).fetchSessionHistory(report, offset, limit);
    }
}

