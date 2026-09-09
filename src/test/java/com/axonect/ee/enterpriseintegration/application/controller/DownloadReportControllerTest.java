package com.axonect.ee.enterpriseintegration.application.controller;

import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.domain.service.impl.DownloadReportServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadReportController Unit Tests")
class DownloadReportControllerTest {

    @Mock
    private DownloadReportServiceImpl downloadReportService;

    @Mock
    private HttpServletRequest httpServletRequest;

    @InjectMocks
    private DownloadReportController downloadReportController;

    @Test
    @DisplayName("downloadReport: a user dump is served as CSV, under the name it was written with")
    void downloadReport_csvReport() throws BaseException, IOException {
        String filename = "42_USER_DATA_DUMP_2026_09_09_00_15_00.csv";
        Resource resource = new ByteArrayResource("USER_ID\nu1\n".getBytes());
        when(downloadReportService.getReportFile(eq(42L), any())).thenReturn(resource);
        when(downloadReportService.getReportFilename(42L)).thenReturn(filename);

        ResponseEntity<Resource> response =
                downloadReportController.downloadReport(42L, httpServletRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"" + filename + "\"");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(resource.contentLength());
        assertThat(response.getBody()).isSameAs(resource);
    }

    @Test
    @DisplayName("downloadReport: a spreadsheet report is served as a spreadsheet, not as text/csv")
    void downloadReport_excelReport() throws BaseException, IOException {
        String filename = "7_AUDIT_LOGS_2026_09_09_00_15_00.xlsx";
        when(downloadReportService.getReportFile(eq(7L), any()))
                .thenReturn(new ByteArrayResource(new byte[]{0x50, 0x4B, 0x03, 0x04}));
        when(downloadReportService.getReportFilename(7L)).thenReturn(filename);

        ResponseEntity<Resource> response =
                downloadReportController.downloadReport(7L, httpServletRequest);

        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }
}
