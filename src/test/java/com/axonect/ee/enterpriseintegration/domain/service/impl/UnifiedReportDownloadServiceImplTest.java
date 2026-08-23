package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.constant.AppConstant;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.mapper.EventMapper;
import com.axonect.ee.enterpriseintegration.domain.repository.DownloadReportRepository;
import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import com.axonect.ee.enterpriseintegration.domain.service.ReportWriter;
import com.axonect.ee.enterpriseintegration.domain.util.ReportDefinitionsRegistry;
import com.axonect.ee.enterpriseintegration.domain.util.ReportFormat;
import com.axonect.ee.enterpriseintegration.domain.util.ReportWriterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UnifiedReportDownloadServiceImpl Unit Tests")
class UnifiedReportDownloadServiceImplTest {

    @InjectMocks
    private UnifiedReportDownloadServiceImpl service;

    @Mock
    private DownloadReportRepository downloadReportRepository;

    @Mock
    private ReportWriterFactory reportWriterFactory;

    @Mock
    private EventMapper eventMapper;

    @Mock
    private ReportDefinition reportDefinition;

    @Mock
    private ReportWriter reportWriter;

    @Mock
    private ExcelReportWriter excelReportWriter;

    private DownloadReport report;
    private final Path fakePath = Paths.get("/tmp/test_report.xlsx");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "defaultOffset", 0);
        ReflectionTestUtils.setField(service, "batchSize", 2);

        report = new DownloadReport();
        report.setId(1L);
        report.setReportType("EVENT_REPORT");
        report.setFormat("EXCEL");
        report.setReportStatus("NOT_STARTED");
        report.setClassificationLevel("CONFIDENTIAL");
    }




    // =========================================================================
    // processReport – successful EXCEL report, ExcelReportWriter.close() called
    // =========================================================================

    @Test
    @DisplayName("processReport: should complete successfully with EXCEL writer and call close()")
    void processReport_success_excelWriter_callsClose() throws Exception {
        when(downloadReportRepository.findById(1L)).thenReturn(Optional.of(report));

        // Single batch: 1 row, totalRecords=1 → (offset=0 + fetched=1) < 1 is false → loop ends
        FilterListViewBatch batch = buildBatch(List.of(Map.of("col1", "val1")), 1);

        try (MockedStatic<ReportDefinitionsRegistry> registry = mockStatic(ReportDefinitionsRegistry.class)) {
            registry.when(() -> ReportDefinitionsRegistry.getDefinition("EVENT_REPORT"))
                    .thenReturn(reportDefinition);


            when(reportDefinition.reportType()).thenReturn("EVENT_REPORT");
            CsvColumn column = new CsvColumn("col1", "val1");
            when(reportDefinition.columns()).thenReturn(List.of(column));
            when(reportDefinition.fetchBatch(any(), eq(0), eq(2))).thenReturn(buildResponse(batch));

            when(reportWriterFactory.get(ReportFormat.EXCEL)).thenReturn(excelReportWriter);
            when(excelReportWriter.init(anyString(), anyList(), anyString())).thenReturn(fakePath);

            service.processReport("1");

            verify(excelReportWriter).init(contains("EVENT_REPORT"), anyList(), eq("CONFIDENTIAL"));
            verify(excelReportWriter).writeBatch(eq(fakePath), anyList(), anyList());
            verify(excelReportWriter).close(fakePath);
        }
    }

    // =========================================================================
    // processReport – successful CSV report (not ExcelReportWriter, close NOT called)
    // =========================================================================

    @Test
    @DisplayName("processReport: should complete with non-Excel writer without calling close()")
    void processReport_success_csvWriter_doesNotCallClose() throws Exception {
        report.setFormat("CSV");
        when(downloadReportRepository.findById(1L)).thenReturn(Optional.of(report));

        FilterListViewBatch batch = buildBatch(List.of(Map.of("col1", "val1")), 1);

        try (MockedStatic<ReportDefinitionsRegistry> registry = mockStatic(ReportDefinitionsRegistry.class)) {
            registry.when(() -> ReportDefinitionsRegistry.getDefinition("EVENT_REPORT"))
                    .thenReturn(reportDefinition);

            when(reportDefinition.reportType()).thenReturn("EVENT_REPORT");
            CsvColumn column = new CsvColumn("col1", "val1");
            when(reportDefinition.columns()).thenReturn(List.of(column));
            when(reportDefinition.fetchBatch(any(), eq(0), eq(2))).thenReturn(buildResponse(batch));

            when(reportWriterFactory.get(ReportFormat.CSV)).thenReturn(reportWriter); // plain mock, not ExcelReportWriter
            when(reportWriter.init(anyString(), anyList(), anyString())).thenReturn(fakePath);

            service.processReport("1");

            verify(reportWriter).writeBatch(eq(fakePath), anyList(), anyList());
            verifyNoInteractions(excelReportWriter); // close() must NOT be called
        }
    }



    // =========================================================================
    // processReport – null classificationLevel defaults to "Open"
    // =========================================================================

    @Test
    @DisplayName("processReport: should use 'Open' as classification level when it is null")
    void processReport_nullClassificationLevel_usesOpen() throws Exception {
        report.setClassificationLevel(null);
        when(downloadReportRepository.findById(1L)).thenReturn(Optional.of(report));

        FilterListViewBatch batch = buildBatch(List.of(Map.of("col1", "v")), 1);

        try (MockedStatic<ReportDefinitionsRegistry> registry = mockStatic(ReportDefinitionsRegistry.class)) {
            registry.when(() -> ReportDefinitionsRegistry.getDefinition("EVENT_REPORT"))
                    .thenReturn(reportDefinition);

            when(reportDefinition.reportType()).thenReturn("EVENT_REPORT");
            CsvColumn column = new CsvColumn("col1", "val1");
            when(reportDefinition.columns()).thenReturn(List.of(column));
            when(reportDefinition.fetchBatch(any(), eq(0), eq(2))).thenReturn(buildResponse(batch));

            when(reportWriterFactory.get(ReportFormat.EXCEL)).thenReturn(excelReportWriter);
            when(excelReportWriter.init(anyString(), anyList(), anyString())).thenReturn(fakePath);

            service.processReport("1");

            verify(excelReportWriter).init(anyString(), anyList(), eq("Open"));
        }
    }






    // =========================================================================
    // handleReportError – second findById returns empty → ifPresent skipped
    // =========================================================================

    @Test
    @DisplayName("handleReportError: should handle empty Optional on retry findById gracefully")
    void processReport_handleReportError_reportNotFoundOnRetry() throws BaseException {
        when(downloadReportRepository.findById(1L))
                .thenReturn(Optional.of(report))    // first call
                .thenReturn(Optional.empty());       // retry in handleReportError

        try (MockedStatic<ReportDefinitionsRegistry> registry = mockStatic(ReportDefinitionsRegistry.class)) {
            registry.when(() -> ReportDefinitionsRegistry.getDefinition(anyString()))
                    .thenThrow(new RuntimeException("trigger error"));

            service.processReport("1");
        }

        // "Failed" publish should never be called since ifPresent was skipped
        verify(eventMapper, never()).publishReportToKafkaEvents(
                argThat(r -> "Failed".equals(r.getReportStatus())), anyString());
    }


    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a FilterListViewBatch using the all-args constructor to correctly
     * populate the primitive int totalRecords field (avoids setter/null issues).
     */
    private FilterListViewBatch buildBatch(List<Map<String, Object>> rows, int totalRecords) {
        // @AllArgsConstructor: tableData, page, pageSize, totalRecords
        return new FilterListViewBatch(rows, 0, rows.size(), totalRecords);
    }

    private CommonAdaptorResp<FilterListViewBatch> buildResponse(FilterListViewBatch batch) {
        CommonAdaptorResp<FilterListViewBatch> response = new CommonAdaptorResp<>();
        response.setData(batch);
        return response;
    }
}