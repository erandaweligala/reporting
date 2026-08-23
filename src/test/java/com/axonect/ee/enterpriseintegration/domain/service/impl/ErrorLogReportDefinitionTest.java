package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.client.MessageLogAdaptor;
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
class ErrorLogReportDefinitionTest {

    @Mock
    private MessageLogAdaptor adaptor;

    private ErrorLogReportDefinition reportDefinition;

    @BeforeEach
    void setUp() {
        reportDefinition = new ErrorLogReportDefinition();
        // Inject mock manually since field is @Autowired
        reportDefinition.adaptor = adaptor;
    }

    @Test
    void reportType_ShouldReturnErrorLogs() {
        assertEquals("ERROR_LOGS", reportDefinition.reportType());
    }

    @Test
    void columns_ShouldReturnAllCsvColumns() {
        List<CsvColumn> columns = reportDefinition.columns();
        assertNotNull(columns);
        assertEquals(16, columns.size());

        assertEquals("id", columns.get(0).getKey());
        assertEquals("ID", columns.get(0).getLabel());

        assertEquals("description", columns.get(9).getKey());
        assertEquals("Description", columns.get(9).getLabel());
    }

    @Test
    void fetchBatch_ShouldCallAdaptorAndReturnResponse() {
        DownloadReport report = new DownloadReport();
        int offset = 0;
        int limit = 10;

        // Prepare mock response
        FilterListViewBatch batch = new FilterListViewBatch();
        batch.setTableData(List.of(
                Map.of("id", 1, "adminUser", "admin")
        ));
        batch.setPage(1);
        batch.setPageSize(10);
        batch.setTotalRecords(1);

        CommonAdaptorResp<FilterListViewBatch> mockResp = new CommonAdaptorResp<>();
        mockResp.setSuccess(true);
        mockResp.setMessage("Success");
        mockResp.setData(batch);

        when(adaptor.fetchActionLogs(report, offset, limit)).thenReturn(mockResp);

        // Call method
        CommonAdaptorResp<FilterListViewBatch> result = reportDefinition.fetchBatch(report, offset, limit);

        // Verify
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("Success", result.getMessage());
        assertEquals(batch, result.getData());

        // Verify adaptor called
        verify(adaptor, times(1)).fetchActionLogs(report, offset, limit);
    }

    @Test
    void fetchBatch_WithDifferentOffsetAndLimit_ShouldPassToAdaptor() {
        DownloadReport report = new DownloadReport();
        int offset = 20;
        int limit = 50;

        CommonAdaptorResp<FilterListViewBatch> mockResp = new CommonAdaptorResp<>();
        when(adaptor.fetchActionLogs(report, offset, limit)).thenReturn(mockResp);

        reportDefinition.fetchBatch(report, offset, limit);

        // Capture arguments
        ArgumentCaptor<DownloadReport> reportCaptor = ArgumentCaptor.forClass(DownloadReport.class);
        ArgumentCaptor<Integer> offsetCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);

        verify(adaptor).fetchActionLogs(reportCaptor.capture(), offsetCaptor.capture(), limitCaptor.capture());

        assertEquals(report, reportCaptor.getValue());
        assertEquals(offset, offsetCaptor.getValue());
        assertEquals(limit, limitCaptor.getValue());
    }
}
