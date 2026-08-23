package com.axonect.ee.enterpriseintegration.domain.mapper;

import com.axonect.ee.enterpriseintegration.application.constant.AppConstant;
import com.axonect.ee.enterpriseintegration.application.transport.request.FilterValue;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.application.util.resultenum.ResponseCodeEnum;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
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

    @BeforeEach
    void setUp() {
        // Inject mocked EntityManager via ReflectionTestUtils (for @PersistenceContext)
        ReflectionTestUtils.setField(downloadReportMapper, "entityManager", entityManager);

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
    @DisplayName("mapDownloadRequestToEntity: format should always be EXCEL")
    void mapDownloadRequestToEntity_formatIsAlwaysExcel() throws JsonProcessingException, BaseException {

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
}