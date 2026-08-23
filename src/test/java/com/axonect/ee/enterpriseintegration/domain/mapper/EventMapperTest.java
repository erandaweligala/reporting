package com.axonect.ee.enterpriseintegration.domain.mapper;

import com.axonect.ee.enterpriseintegration.application.constant.AppConstant;
import com.axonect.ee.enterpriseintegration.application.transport.request.DBWriteRequestGeneric;
import com.axonect.ee.enterpriseintegration.application.transport.response.PublishResult;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.application.util.resultenum.ResponseCodeEnum;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.application.util.KafkaEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventMapper Unit Tests")
class EventMapperTest {

    @InjectMocks
    private EventMapper eventMapper;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private PublishResult publishResult;

    private DownloadReport report;

    @BeforeEach
    void setUp() {
        report = new DownloadReport();
        report.setId(1L);
        report.setReportType("SUMMARY");
        report.setReportName("summary_report.xlsx");
        report.setReportStatus("Completed");
        report.setFormat("EXCEL");
        report.setFilterValuesJson("{\"key\":\"value\"}");
        report.setDescription("Test report");
        report.setClassificationLevel("CONFIDENTIAL");
        report.setExecutedUser("executor");
        report.setCreatedBy("creator");
        report.setCreatedAt(new Date());
        report.setUpdatedBy("updater");
        report.setUpdatedAt(new Date());
        report.setLastUpdatedAt(new Date());
    }

    // =========================================================================
    // toDBWriteEvent – field mapping
    // =========================================================================

    @Test
    @DisplayName("toDBWriteEvent: should map all fields correctly into DBWriteRequestGeneric")
    void toDBWriteEvent_mapsAllFieldsCorrectly() {
        DBWriteRequestGeneric result = eventMapper.toDBWriteEvent("INSERT", report, "testUser");

        assertThat(result).isNotNull();
        assertThat(result.getEventType()).isEqualTo("INSERT");
        assertThat(result.getUserName()).isEqualTo("testUser");
        assertThat(result.getTableName()).isEqualTo("REPORT_DOWNLOAD");
        assertThat(result.getTimestamp()).isNotNull();

        // whereConditions
        assertThat(result.getWhereConditions()).containsEntry("ID", 1L);

        // columnValues
        assertThat(result.getColumnValues())
                .containsEntry("ID",                   report.getId())
                .containsEntry("REPORT_TYPE",          report.getReportType())
                .containsEntry("REPORT_NAME",          report.getReportName())
                .containsEntry("REPORT_STATUS",        report.getReportStatus())
                .containsEntry("FORMAT",               report.getFormat())
                .containsEntry("FILTER_VALUES",        report.getFilterValuesJson())
                .containsEntry("DESCRIPTION",          report.getDescription())
                .containsEntry("CLASSIFICATION_LEVEL", report.getClassificationLevel())
                .containsEntry("EXECUTED_USER",        report.getExecutedUser())
                .containsEntry("CREATED_BY",           report.getCreatedBy())
                .containsEntry("UPDATED_BY",           report.getUpdatedBy());
    }

    @Test
    @DisplayName("toDBWriteEvent: timestamp should follow yyyy-MM-dd'T'HH:mm:ss.SSS format")
    void toDBWriteEvent_timestampFormat_isCorrect() {
        DBWriteRequestGeneric result = eventMapper.toDBWriteEvent("UPDATE", report, "user1");

        // e.g. "2025-03-04T10:30:45.123"
        assertThat(result.getTimestamp())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}");
    }

    @Test
    @DisplayName("toDBWriteEvent: should handle null report fields without throwing")
    void toDBWriteEvent_nullReportFields_doesNotThrow() {
        DownloadReport emptyReport = new DownloadReport();
        emptyReport.setId(99L);

        assertThatCode(() -> eventMapper.toDBWriteEvent("INSERT", emptyReport, "user"))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // publishReportToKafkaEvents – both success
    // =========================================================================

    @Test
    @DisplayName("publishReportToKafkaEvents: should publish without throwing when both DC and DR succeed")
    void publishReportToKafkaEvents_bothSuccess_doesNotThrow() throws Exception {
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(publishResult);
        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isBothSuccess()).thenReturn(true);

        assertThatCode(() -> eventMapper.publishReportToKafkaEvents(report, AppConstant.UPDATE))
                .doesNotThrowAnyException();

        verify(kafkaEventPublisher).publishDBWriteEvent(any(DBWriteRequestGeneric.class));
    }

    // =========================================================================
    // publishReportToKafkaEvents – partial failure (warn but no exception)
    // =========================================================================

    @Test
    @DisplayName("publishReportToKafkaEvents: should log warning but not throw on partial failure")
    void publishReportToKafkaEvents_partialFailure_doesNotThrow() throws Exception {
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(publishResult);
        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isBothSuccess()).thenReturn(false);
        when(publishResult.isDcSuccess()).thenReturn(true);
        when(publishResult.isDrSuccess()).thenReturn(false);

        assertThatCode(() -> eventMapper.publishReportToKafkaEvents(report, AppConstant.UPDATE))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // publishReportToKafkaEvents – complete failure → BaseException
    // =========================================================================

    @Test
    @DisplayName("publishReportToKafkaEvents: should throw BaseException with OPERATION_FAILED on complete failure")
    void publishReportToKafkaEvents_completeFailure_throwsBaseException() throws Exception {
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(publishResult);
        when(publishResult.isCompleteFailure()).thenReturn(true);

        assertThatThrownBy(() -> eventMapper.publishReportToKafkaEvents(report, AppConstant.UPDATE))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> {
                    BaseException base = (BaseException) ex;
                    assertThat(base.getResponseCode()).isEqualTo(ResponseCodeEnum.OPERATION_FAILED.code());
                    assertThat(base.getMessage()).contains("Failed to publish report download events to Kafka");
                });
    }


    // =========================================================================
    // publishReportToKafkaEvents – unexpected RuntimeException → wrapped BaseException
    // =========================================================================

    @Test
    @DisplayName("publishReportToKafkaEvents: should wrap unexpected RuntimeException in BaseException")
    void publishReportToKafkaEvents_unexpectedException_wrapsInBaseException() throws Exception {
        when(kafkaEventPublisher.publishDBWriteEvent(any()))
                .thenThrow(new RuntimeException("unexpected broker error"));

        assertThatThrownBy(() -> eventMapper.publishReportToKafkaEvents(report, AppConstant.UPDATE))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> {
                    BaseException base = (BaseException) ex;
                    assertThat(base.getResponseCode()).isEqualTo(ResponseCodeEnum.OPERATION_FAILED.code());
                    assertThat(base.getMessage()).contains("Failed to publish report download events");
                });
    }

    // =========================================================================
    // publishReportToKafkaEvents – correct event type and SYSTEM user passed
    // =========================================================================

    @Test
    @DisplayName("publishReportToKafkaEvents: should pass operation as eventType and SYSTEM as userName")
    void publishReportToKafkaEvents_passesCorrectEventTypeAndSystemUser() throws Exception {
        when(kafkaEventPublisher.publishDBWriteEvent(any())).thenReturn(publishResult);
        when(publishResult.isCompleteFailure()).thenReturn(false);
        when(publishResult.isBothSuccess()).thenReturn(true);

        eventMapper.publishReportToKafkaEvents(report, AppConstant.UPDATE);

        ArgumentCaptor<DBWriteRequestGeneric> captor = ArgumentCaptor.forClass(DBWriteRequestGeneric.class);
        verify(kafkaEventPublisher).publishDBWriteEvent(captor.capture());

        DBWriteRequestGeneric captured = captor.getValue();
        assertThat(captured.getEventType()).isEqualTo(AppConstant.UPDATE);
        assertThat(captured.getUserName()).isEqualTo(AppConstant.SYSTEM);
        assertThat(captured.getTableName()).isEqualTo("REPORT_DOWNLOAD");
    }
}