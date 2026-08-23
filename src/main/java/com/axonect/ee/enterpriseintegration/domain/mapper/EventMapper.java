package com.axonect.ee.enterpriseintegration.domain.mapper;

import com.axonect.ee.enterpriseintegration.application.constant.AppConstant;
import com.axonect.ee.enterpriseintegration.application.transport.request.DBWriteRequestGeneric;
import com.axonect.ee.enterpriseintegration.application.transport.response.PublishResult;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.application.util.resultenum.ResponseCodeEnum;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.application.util.KafkaEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;


@Component
@Slf4j
public class EventMapper {

    private final KafkaEventPublisher kafkaEventPublisher;
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private String formatDate(Date date) {
        if (date == null) return null;
        return new SimpleDateFormat(TIMESTAMP_FORMAT).format(date);
    }

    public EventMapper(KafkaEventPublisher kafkaEventPublisher) {
        this.kafkaEventPublisher = kafkaEventPublisher;
    }

    /**
     * Create DBWriteRequestGeneric for Report Download
     */
    public DBWriteRequestGeneric toDBWriteEvent(
            String eventType,
            DownloadReport report,
            String userName
    ) {
        Map<String, Object> columnValues = buildPlanColumnValues(report);
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("ID", report.getId());

        return DBWriteRequestGeneric.builder()
                .eventType(eventType)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userName(userName)
                .columnValues(columnValues)
                .whereConditions(whereConditions)
                .tableName("REPORT_DOWNLOAD")
                .build();
    }

    private Map<String, Object> buildPlanColumnValues(DownloadReport report) {
        Map<String, Object> columns = new HashMap<>();

        columns.put("ID", report.getId());
        columns.put("REPORT_TYPE", report.getReportType());
        columns.put("REPORT_NAME", report.getReportName());
        columns.put("REPORT_STATUS", report.getReportStatus());
        columns.put("FORMAT", report.getFormat());
        columns.put("FILTER_VALUES", report.getFilterValuesJson());
        columns.put("DESCRIPTION", report.getDescription());
        columns.put("CLASSIFICATION_LEVEL", report.getClassificationLevel());

        /* ---------- Execution ---------- */
        columns.put("EXECUTED_USER", report.getExecutedUser());

        /* ---------- Creation ---------- */
        columns.put("CREATED_BY", report.getCreatedBy());

        /* ---------- Update ---------- */
        columns.put("UPDATED_BY", report.getUpdatedBy());

        columns.put("CREATED_AT", formatDate(report.getCreatedAt()));
        columns.put("LAST_UPDATED_AT", formatDate(report.getLastUpdatedAt()));
        columns.put("UPDATED_AT", formatDate(report.getUpdatedAt()));

        return columns;
    }


    public void publishReportToKafkaEvents(DownloadReport report, String operation) throws BaseException {
        try {
            DBWriteRequestGeneric mainEvent = toDBWriteEvent(operation, report, AppConstant.SYSTEM);

            PublishResult result = kafkaEventPublisher.publishDBWriteEvent(mainEvent);

            if (result.isCompleteFailure()) {
                log.error("Complete failure publishing Report download events for report '{}'", report.getReportName());
                throw new BaseException(
                        ResponseCodeEnum.OPERATION_FAILED.code(),
                        "Failed to publish report download events to Kafka"
                );
            }

            if (!result.isBothSuccess()) {
                log.warn("Partial failure publishing Report download events for report '{}': DC={}, DR={}",
                        report.getReportName(), result.isDcSuccess(), result.isDrSuccess());
            }

            log.info("Report download events published");

        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to publish report download events for report '{}'", report.getReportName(), e);
            throw new BaseException(
                    ResponseCodeEnum.OPERATION_FAILED.code(),
                    "Failed to publish report download events"
            );
        }
    }

}
