package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.client.SubscriberDetailsAdaptor;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriberDetailsReportDefinitionTest {

    @Mock
    private SubscriberDetailsAdaptor adaptor;

    private SubscriberDetailsReportDefinition reportDefinition;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reportDefinition = new SubscriberDetailsReportDefinition();
        reportDefinition.adaptor = adaptor; // manually inject mock
    }

    @Test
    void reportType_ShouldReturnSubscriberDetails() {
        String type = reportDefinition.reportType();
        assertEquals("SUBSCRIBER_DETAILS", type);
    }

    @Test
    void columns_ShouldReturnAllCsvColumns() {
        List<CsvColumn> columns = reportDefinition.columns();
        assertNotNull(columns);
        assertEquals(29, columns.size());
        assertEquals("user_id", columns.get(0).getKey());
        assertEquals("last_updated_timestamp", columns.get(28).getKey());
    }

    @Test
    void fetchBatch_ShouldDelegateToAdaptor() {
        DownloadReport report = new DownloadReport();
        int offset = 0;
        int limit = 10;

        CommonAdaptorResp<FilterListViewBatch> mockResp = new CommonAdaptorResp<>();
        when(adaptor.fetchSubscriberDetails(report, offset, limit)).thenReturn(mockResp);

        CommonAdaptorResp<FilterListViewBatch> result = reportDefinition.fetchBatch(report, offset, limit);

        assertNotNull(result);
        verify(adaptor, times(1)).fetchSubscriberDetails(report, offset, limit);
    }
}
