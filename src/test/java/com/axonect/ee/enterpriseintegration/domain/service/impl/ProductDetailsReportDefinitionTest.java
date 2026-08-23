package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.client.ProductDetailsAdaptor;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductDetailsReportDefinitionTest {

    @Mock
    private ProductDetailsAdaptor adaptor;

    private ProductDetailsReportDefinition reportDefinition;

    @BeforeEach
    void setUp() {
        reportDefinition = new ProductDetailsReportDefinition();
        // Inject mock manually since field is @Autowired
        reportDefinition.adaptor = adaptor;
    }

    @Test
    void reportType_ShouldReturnProductDetails() {
        assertEquals("PRODUCT_DETAILS", reportDefinition.reportType());
    }

    @Test
    void columns_ShouldReturnAllCsvColumns() {
        List<CsvColumn> columns = reportDefinition.columns();
        assertNotNull(columns);
        assertEquals(12, columns.size());

        assertEquals("planInternalId", columns.get(0).getKey());
        assertEquals("Plan Internal ID", columns.get(0).getLabel());

        assertEquals("bucketList", columns.get(11).getKey());
        assertEquals("Bucket List", columns.get(11).getLabel());
    }

    @Test
    void fetchBatch_ShouldCallAdaptorAndReturnResponse() {
        DownloadReport report = new DownloadReport();
        int offset = 0;
        int limit = 10;

        // Prepare mock response
        FilterListViewBatch batch = new FilterListViewBatch();
        batch.setTableData(List.of(Map.of("planId", "123", "planName", "Premium Plan")));
        batch.setPage(1);
        batch.setPageSize(10);
        batch.setTotalRecords(1);

        CommonAdaptorResp<FilterListViewBatch> mockResp = new CommonAdaptorResp<>();
        mockResp.setSuccess(true);
        mockResp.setMessage("Success");
        mockResp.setData(batch);

        when(adaptor.fetchProductDetails(report, offset, limit)).thenReturn(mockResp);

        // Call method
        CommonAdaptorResp<FilterListViewBatch> result = reportDefinition.fetchBatch(report, offset, limit);

        // Verify
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("Success", result.getMessage());
        assertEquals(batch, result.getData());

        verify(adaptor, times(1)).fetchProductDetails(report, offset, limit);
    }

    @Test
    void fetchBatch_WithDifferentOffsetAndLimit_ShouldPassToAdaptor() {
        DownloadReport report = new DownloadReport();
        int offset = 50;
        int limit = 25;

        CommonAdaptorResp<FilterListViewBatch> mockResp = new CommonAdaptorResp<>();
        when(adaptor.fetchProductDetails(report, offset, limit)).thenReturn(mockResp);

        reportDefinition.fetchBatch(report, offset, limit);

        // Capture arguments
        ArgumentCaptor<DownloadReport> reportCaptor = ArgumentCaptor.forClass(DownloadReport.class);
        ArgumentCaptor<Integer> offsetCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);

        verify(adaptor).fetchProductDetails(reportCaptor.capture(), offsetCaptor.capture(), limitCaptor.capture());

        assertEquals(report, reportCaptor.getValue());
        assertEquals(offset, offsetCaptor.getValue());
        assertEquals(limit, limitCaptor.getValue());
    }

    @Test
    void fetchBatch_WhenAdaptorReturnsNull_ShouldReturnNullData() {
        DownloadReport report = new DownloadReport();
        int offset = 0;
        int limit = 10;

        when(adaptor.fetchProductDetails(report, offset, limit)).thenReturn(null);

        CommonAdaptorResp<FilterListViewBatch> result = reportDefinition.fetchBatch(report, offset, limit);

        // Expect null if adaptor returns null
        assertNull(result);
        verify(adaptor).fetchProductDetails(report, offset, limit);
    }
}

