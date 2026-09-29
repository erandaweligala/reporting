package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.config.ScheduledDumpProperties;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import com.axonect.ee.enterpriseintegration.domain.service.DownloadReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledDumpJobTest {

    private final DownloadReportService downloadReportService = mock(DownloadReportService.class);
    private final ScheduledDumpProperties properties = new ScheduledDumpProperties();
    private final ScheduledDumpJob job = new ScheduledDumpJob(downloadReportService, properties);

    @Test
    void requestsEachReportTypeTheWayTheCreateEndpointDoes() throws Exception {
        job.run(List.of("USER_DATA_DUMP", "MAC_SERVICE_TABLE", "PLAN_TO_BUCKET", "BUCKET_INSTANCE"));

        List<DownloadReportRequest> requests = requests(4);
        assertEquals(List.of("USER_DATA_DUMP", "MAC_SERVICE_TABLE", "PLAN_TO_BUCKET", "BUCKET_INSTANCE"),
                requests.stream().map(DownloadReportRequest::getReportType).toList());
        for (DownloadReportRequest request : requests) {
            assertEquals("SCHEDULER", request.getCreatedBy());
            // Left to the report type, which resolves a streaming dump to CSV.
            assertNull(request.getFormat());
            assertNull(request.getFilterValues());
        }
    }

    @Test
    void recordsTheConfiguredCreator() throws Exception {
        properties.setCreatedBy("nightly-job");

        job.run(List.of("PLAN_TO_BUCKET"));

        assertEquals("nightly-job", requests(1).get(0).getCreatedBy());
    }

    @Test
    void oneReportThatCannotBeRequestedDoesNotCostTheOthers() throws Exception {
        when(downloadReportService.createDownloadReportRequest(any()))
                .thenThrow(new BaseException("500", "database unavailable"))
                .thenThrow(new IllegalStateException("unexpected"))
                .thenReturn(null);

        job.run(List.of("USER_DATA_DUMP", "MAC_SERVICE_TABLE", "PLAN_TO_BUCKET"));

        assertEquals(List.of("USER_DATA_DUMP", "MAC_SERVICE_TABLE", "PLAN_TO_BUCKET"),
                requests(3).stream().map(DownloadReportRequest::getReportType).toList());
    }

    private List<DownloadReportRequest> requests(int expected) throws Exception {
        ArgumentCaptor<DownloadReportRequest> captor = ArgumentCaptor.forClass(DownloadReportRequest.class);
        verify(downloadReportService, times(expected)).createDownloadReportRequest(captor.capture());
        return captor.getAllValues();
    }
}
