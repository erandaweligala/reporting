package com.axonect.ee.enterpriseintegration.domain.mapper;

import com.axonect.ee.enterpriseintegration.application.constant.AppConstant;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.application.util.resultenum.ResponseCodeEnum;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import com.axonect.ee.enterpriseintegration.domain.util.ReportDefinitionsRegistry;
import com.axonect.ee.enterpriseintegration.domain.util.ReportFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
@Slf4j
public class DownloadReportMapper {
    @PersistenceContext
    private EntityManager entityManager;


    public DownloadReportMapper() {
    }

    public DownloadReport mapDownloadRequestToEntity(DownloadReportRequest downloadReportRequest) throws JsonProcessingException, BaseException {
        ObjectMapper objectMapper = new ObjectMapper();
        DownloadReport report = new DownloadReport();
        report.setCreatedBy(downloadReportRequest.getCreatedBy());
        report.setReportType(downloadReportRequest.getReportType());
        report.setReportStatus(AppConstant.NOT_STARTED);
        report.setFormat(resolveFormat(downloadReportRequest.getReportType(), downloadReportRequest.getFormat()));
        report.setFilterValuesJson(downloadReportRequest.getFilterValues() != null ? objectMapper.writeValueAsString(downloadReportRequest.getFilterValues()) : null);
        report.setClassificationLevel(downloadReportRequest.getClassificationLevel());

        Date now = new Date();
        report.setCreatedAt(now);
        report.setLastUpdatedAt(now);
        log.debug("Report entity mapper : {}",report);
        return report;

    }

    /**
     * Settles the format the report will be written in.
     *
     * <p>A streaming report writes itself straight into a CSV file and has no spreadsheet form —
     * {@code UnifiedReportDownloadServiceImpl} rejects any other format once the run is already
     * asynchronous, where the caller only sees the request end as Failed. So the choice is made
     * here instead: such a report defaults to CSV, and asking for a spreadsheet of it is refused
     * on the request itself. Everything else keeps defaulting to EXCEL, as it did before a format
     * could be asked for at all.
     */
    private String resolveFormat(String reportType, String requestedFormat) throws BaseException {
        boolean streaming = ReportDefinitionsRegistry.isStreaming(reportType);

        if (requestedFormat == null || requestedFormat.isBlank()) {
            return streaming ? ReportFormat.CSV.name() : AppConstant.EXCEL;
        }

        ReportFormat format = parseFormat(requestedFormat);
        if (streaming && format != ReportFormat.CSV) {
            throw new BaseException(ResponseCodeEnum.BAD_REQUEST.code(),
                    reportType + " is produced as CSV only; a spreadsheet cannot hold a report of "
                            + "this size. Requested format: " + format);
        }
        return format.name();
    }

    private ReportFormat parseFormat(String requestedFormat) throws BaseException {
        try {
            return ReportFormat.valueOf(requestedFormat.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BaseException(ResponseCodeEnum.BAD_REQUEST_INVALID_FIELDS.code(),
                    "Unknown report format: " + requestedFormat + ". Supported formats: "
                            + Arrays.stream(ReportFormat.values())
                                    .map(Enum::name)
                                    .collect(Collectors.joining(", ")));
        }
    }

}
