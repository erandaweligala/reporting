package com.axonect.ee.enterpriseintegration.domain.mapper;

import com.axonect.ee.enterpriseintegration.application.constant.AppConstant;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

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
        report.setFormat(AppConstant.EXCEL);
        report.setFilterValuesJson(downloadReportRequest.getFilterValues() != null ? objectMapper.writeValueAsString(downloadReportRequest.getFilterValues()) : null);
        report.setClassificationLevel(downloadReportRequest.getClassificationLevel());

        Date now = new Date();
        report.setCreatedAt(now);
        report.setLastUpdatedAt(now);
        log.debug("Report entity mapper : {}",report);
        return report;

    }

}
