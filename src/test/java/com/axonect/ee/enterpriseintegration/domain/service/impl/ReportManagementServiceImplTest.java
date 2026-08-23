package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.request.FilterRequest;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.PagedReportDetails;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.repository.DownloadReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportManagementServiceImplTest {

    @Mock
    private DownloadReportRepository downloadReportRepository;

    private ReportManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ReportManagementServiceImpl(downloadReportRepository);
    }

    @Test
    void filterReports_ShouldReturnPagedReports() throws BaseException {
        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setPage(1);
        filterRequest.setPageSize(2);
        filterRequest.setReportType("ERROR_LOGS");

        List<DownloadReport> content = List.of(new DownloadReport(), new DownloadReport());
        Page<DownloadReport> page = new PageImpl<>(content, PageRequest.of(0, 2), 5);

        when(downloadReportRepository.findFilteredReports(
                any(), eq("ERROR_LOGS"), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        CommonAdaptorResp<PagedReportDetails> response = service.filterReports(filterRequest);

        assertTrue(response.isSuccess());
        assertEquals(2, response.getData().getReportDetails().size());
        assertEquals(1, response.getData().getPage());
        assertEquals(2, response.getData().getPageSize());
        assertEquals(5, response.getData().getTotalRecords());

        verify(downloadReportRepository, times(1))
                .findFilteredReports(any(), eq("ERROR_LOGS"), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void filterReports_WhenRepositoryThrowsException_ShouldThrowBaseException() {
        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setPage(1);
        filterRequest.setPageSize(1);

        when(downloadReportRepository.findFilteredReports(
                any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenThrow(new RuntimeException("DB error"));

        BaseException ex = assertThrows(BaseException.class, () -> service.filterReports(filterRequest));
        assertEquals("5000", ex.getResponseCode()); // assuming your enum maps to this code
        assertEquals("Internal Server Error", ex.getMessage());
    }

    @Test
    void filterReports_ShouldHandleEmptyResult() throws BaseException {
        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setPage(1);
        filterRequest.setPageSize(10);

        Page<DownloadReport> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(downloadReportRepository.findFilteredReports(
                any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(emptyPage);

        CommonAdaptorResp<PagedReportDetails> response = service.filterReports(filterRequest);

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertEquals(0, response.getData().getTotalRecords());
        assertEquals(0, response.getData().getReportDetails().size());
    }
}
