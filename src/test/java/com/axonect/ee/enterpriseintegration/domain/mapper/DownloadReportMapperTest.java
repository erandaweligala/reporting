package com.axonect.ee.enterpriseintegration.domain.mapper;

import com.axonect.ee.enterpriseintegration.application.constant.AppConstant;
import com.axonect.ee.enterpriseintegration.application.transport.request.FilterValue;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.application.util.resultenum.ResponseCodeEnum;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import com.axonect.ee.enterpriseintegration.domain.service.StreamingReportDefinition;
import com.axonect.ee.enterpriseintegration.domain.util.ReportDefinitionsRegistry;
import com.axonect.ee.enterpriseintegration.domain.util.ReportFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadReportMapper Unit Tests")
class DownloadReportMapperTest {

    @InjectMocks
    private DownloadReportMapper downloadReportMapper;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    private DownloadReportRequest downloadReportRequest;

    /** The report type the tests register a streaming definition under. */
    private static final String STREAMING_REPORT_TYPE = "USER_DATA_DUMP";

    @BeforeEach
    void setUp() throws Exception {
        // Inject mocked EntityManager via ReflectionTestUtils (for @PersistenceContext)
        ReflectionTestUtils.setField(downloadReportMapper, "entityManager", entityManager);

        // The registry is static and shared: start from a known state, then register only the
        // streaming definition the format tests need. SUMMARY stays unregistered, so it takes the
        // paged path the way any ordinary report type does.
        clearRegistry();
        ReportDefinitionsRegistry.register(STREAMING_REPORT_TYPE, mock(StreamingReportDefinition.class));

        FilterValue filter1 = new FilterValue();
        filter1.setColumnName("status");
        filter1.setValue("ACTIVE");

        FilterValue filter2 = new FilterValue();
        filter2.setColumnName("msisdn");
        filter2.setValue("94770000000");


        downloadReportRequest = new DownloadReportRequest();
        downloadReportRequest.setCreatedBy("testUser");
        downloadReportRequest.setReportType("SUMMARY");
        downloadReportRequest.setClassificationLevel("CONFIDENTIAL");
        downloadReportRequest.setFilterValues(List.of(filter1, filter2));
    }


    // -------------------------------------------------------------------------
    // mapDownloadRequestToEntity – null filterValues
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mapDownloadRequestToEntity: should set filterValuesJson to null when filterValues is null")
    void mapDownloadRequestToEntity_nullFilterValues_setsJsonToNull() throws JsonProcessingException, BaseException {
        downloadReportRequest.setFilterValues(null);

        DownloadReport result = downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest);

        assertThat(result.getFilterValuesJson()).isNull();
    }




    // -------------------------------------------------------------------------
    // Default constructor
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("constructor: should instantiate DownloadReportMapper without errors")
    void constructor_instantiatesSuccessfully() {
        assertThatCode(DownloadReportMapper::new).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Field-level assertions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mapDownloadRequestToEntity: report status should always be NOT_STARTED")
    void mapDownloadRequestToEntity_reportStatusIsAlwaysNotStarted() throws JsonProcessingException, BaseException {

        DownloadReport result = downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest);

        assertThat(result.getReportStatus()).isEqualTo(AppConstant.NOT_STARTED);
    }

    @Test
    @DisplayName("mapDownloadRequestToEntity: a paged report with no format asked for stays EXCEL")
    void mapDownloadRequestToEntity_pagedReportDefaultsToExcel() throws JsonProcessingException, BaseException {

        DownloadReport result = downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest);

        assertThat(result.getFormat()).isEqualTo(AppConstant.EXCEL);
    }

    // -------------------------------------------------------------------------
    // Format resolution
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mapDownloadRequestToEntity: a streaming report with no format asked for is CSV")
    void mapDownloadRequestToEntity_streamingReportDefaultsToCsv() throws JsonProcessingException, BaseException {
        downloadReportRequest.setReportType(STREAMING_REPORT_TYPE);

        DownloadReport result = downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest);

        assertThat(result.getFormat()).isEqualTo(ReportFormat.CSV.name());
    }

    @Test
    @DisplayName("mapDownloadRequestToEntity: a streaming report may ask for CSV, in any case")
    void mapDownloadRequestToEntity_streamingReportAcceptsCsv() throws JsonProcessingException, BaseException {
        downloadReportRequest.setReportType(STREAMING_REPORT_TYPE);
        downloadReportRequest.setFormat(" csv ");

        DownloadReport result = downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest);

        assertThat(result.getFormat()).isEqualTo(ReportFormat.CSV.name());
    }

    @Test
    @DisplayName("mapDownloadRequestToEntity: a spreadsheet of a streaming report is refused on the request")
    void mapDownloadRequestToEntity_streamingReportRejectsExcel() {
        downloadReportRequest.setReportType(STREAMING_REPORT_TYPE);
        downloadReportRequest.setFormat(AppConstant.EXCEL);

        BaseException ex = assertThrows(BaseException.class,
                () -> downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest));

        assertThat(ex.getMessage()).contains("CSV only");
        assertThat(ex.getResponseCode()).isEqualTo(ResponseCodeEnum.BAD_REQUEST.code());
    }

    @Test
    @DisplayName("mapDownloadRequestToEntity: a paged report may ask for CSV")
    void mapDownloadRequestToEntity_pagedReportAcceptsCsv() throws JsonProcessingException, BaseException {
        downloadReportRequest.setFormat("CSV");

        DownloadReport result = downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest);

        assertThat(result.getFormat()).isEqualTo(ReportFormat.CSV.name());
    }

    @Test
    @DisplayName("mapDownloadRequestToEntity: an unknown format is refused, naming the ones there are")
    void mapDownloadRequestToEntity_unknownFormatIsRejected() {
        downloadReportRequest.setFormat("PDF");

        BaseException ex = assertThrows(BaseException.class,
                () -> downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest));

        assertThat(ex.getMessage()).contains("PDF", "CSV", "EXCEL");
        assertThat(ex.getResponseCode()).isEqualTo(ResponseCodeEnum.BAD_REQUEST_INVALID_FIELDS.code());
    }

    @Test
    @DisplayName("mapDownloadRequestToEntity: a blank format falls back to the report's default")
    void mapDownloadRequestToEntity_blankFormatFallsBackToDefault() throws JsonProcessingException, BaseException {
        downloadReportRequest.setFormat("   ");

        DownloadReport result = downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest);

        assertThat(result.getFormat()).isEqualTo(AppConstant.EXCEL);
    }

    @Test
    @DisplayName("mapDownloadRequestToEntity: filterValuesJson should be valid JSON string when filterValues provided")
    void mapDownloadRequestToEntity_filterValuesJson_isValidJson() throws JsonProcessingException, BaseException {
        FilterValue filter1 = new FilterValue();
        filter1.setColumnName("status");
        filter1.setValue("ACTIVE");

        downloadReportRequest.setFilterValues(List.of(filter1));

        DownloadReport result = downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest);

        assertThat(result.getFilterValuesJson())
                .contains("status")
                .contains("ACTIVE");
    }

    private static void clearRegistry() throws Exception {
        var field = ReportDefinitionsRegistry.class.getDeclaredField("registry");
        field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).clear();
    }
}
