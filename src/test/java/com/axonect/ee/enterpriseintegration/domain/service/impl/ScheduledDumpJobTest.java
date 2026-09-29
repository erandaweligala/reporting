package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.config.ScheduledDumpProperties;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import com.axonect.ee.enterpriseintegration.domain.service.DownloadReportService;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScheduledDumpJobTest {

    private final DownloadReportService downloadReportService = mock(DownloadReportService.class);
    private final ScheduledDumpProperties properties = new ScheduledDumpProperties();
    private final LockProvider lockProvider = mock(LockProvider.class);
    private final SimpleLock lock = mock(SimpleLock.class);
    private final ScheduledDumpJob job = new ScheduledDumpJob(downloadReportService, properties, lockProvider);

    @BeforeEach
    void setUp() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));
    }

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

    @Test
    void holdsTheLockWhileRequestingAndLetsItGoAfter() throws Exception {
        properties.getLock().setAtLeastFor(Duration.ofMinutes(7));
        properties.getLock().setAtMostFor(Duration.ofMinutes(20));

        job.run(List.of("PLAN_TO_BUCKET"));

        ArgumentCaptor<LockConfiguration> captor = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(captor.capture());
        assertEquals("scheduled-dump", captor.getValue().getName());
        assertEquals(Duration.ofMinutes(7), captor.getValue().getLockAtLeastFor());
        assertEquals(Duration.ofMinutes(20), captor.getValue().getLockAtMostFor());
        requests(1);
        verify(lock).unlock();
    }

    @Test
    void standsDownWhenAnotherInstanceHoldsTheLock() {
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        job.run(List.of("USER_DATA_DUMP", "PLAN_TO_BUCKET"));

        verifyNoInteractions(downloadReportService);
    }

    @Test
    void standsDownWhenTheLockCannotBeRead() {
        when(lockProvider.lock(any())).thenThrow(new IllegalStateException("ORA-00942: table or view does not exist"));

        job.run(List.of("USER_DATA_DUMP", "PLAN_TO_BUCKET"));

        verifyNoInteractions(downloadReportService);
    }

    @Test
    void requestsWithoutTheLockWhenItIsSwitchedOff() throws Exception {
        properties.getLock().setEnabled(false);

        job.run(List.of("USER_DATA_DUMP"));

        verifyNoInteractions(lockProvider);
        requests(1);
    }

    @Test
    void letsTheLockGoEvenWhenEveryRequestFails() throws Exception {
        when(downloadReportService.createDownloadReportRequest(any()))
                .thenThrow(new BaseException("500", "database unavailable"));

        job.run(List.of("USER_DATA_DUMP"));

        verify(lock).unlock();
    }

    private List<DownloadReportRequest> requests(int expected) throws Exception {
        ArgumentCaptor<DownloadReportRequest> captor = ArgumentCaptor.forClass(DownloadReportRequest.class);
        verify(downloadReportService, times(expected)).createDownloadReportRequest(captor.capture());
        return captor.getAllValues();
    }
}
