package com.axonect.ee.enterpriseintegration.domain.mapper;

import com.axonect.ee.enterpriseintegration.application.constant.AppConstant;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import com.axonect.ee.enterpriseintegration.domain.service.StreamingReportDefinition;
import com.axonect.ee.enterpriseintegration.domain.util.ReportDefinitionsRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Locale;

@Component
@Slf4j
public class DownloadReportMapper {
    @PersistenceContext
    private EntityManager entityManager;


    public DownloadReportMapper() {
    }

    public DownloadReport mapDownloadRequestToEntity(DownloadReportRequest downloadReportRequest) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        DownloadReport report = new DownloadReport();
        report.setCreatedBy(downloadReportRequest.getCreatedBy());
        report.setReportType(downloadReportRequest.getReportType());
        report.setReportStatus(AppConstant.NOT_STARTED);
        report.setFormat(resolveFormat(downloadReportRequest));
        report.setFilterValuesJson(downloadReportRequest.getFilterValues() != null ? objectMapper.writeValueAsString(downloadReportRequest.getFilterValues()) : null);
        report.setClassificationLevel(downloadReportRequest.getClassificationLevel());

        Date now = new Date();
        report.setCreatedAt(now);
        report.setLastUpdatedAt(now);
        log.debug("Report entity mapper : {}",report);
        return report;

    }

    /**
     * The format the report is written in: what the caller asked for, and otherwise what the
     * report type can actually produce. A streaming definition writes a file too large for a
     * spreadsheet and rejects EXCEL outright, so defaulting those to CSV is what lets a plain
     * create request for USER_DATA_DUMP finish and be downloaded; every other type keeps the
     * EXCEL default it has always had.
     */
    private String resolveFormat(DownloadReportRequest downloadReportRequest) {
        String requested = downloadReportRequest.getFormat();
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase(Locale.ROOT);
        }
        return isCsvOnly(downloadReportRequest.getReportType()) ? AppConstant.CSV : AppConstant.EXCEL;
    }

    private boolean isCsvOnly(String reportType) {
        try {
            return ReportDefinitionsRegistry.getDefinition(reportType) instanceof StreamingReportDefinition;
        } catch (IllegalArgumentException e) {
            // Unknown report type — leave it to the download pipeline, which fails it with a
            // message naming the type rather than a format the caller never mentioned.
            log.debug("No definition registered for report type {} while resolving format", reportType);
            return false;
        }
    }

}
