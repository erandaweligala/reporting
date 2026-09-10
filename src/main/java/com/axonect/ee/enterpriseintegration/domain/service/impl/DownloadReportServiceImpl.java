package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.constant.AppConstant;
import com.axonect.ee.enterpriseintegration.application.constant.LoggingAdviceConstants;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.util.exception.StackTraceTracker;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.application.util.resultenum.ResponseCodeEnum;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import com.axonect.ee.enterpriseintegration.domain.mapper.DownloadReportMapper;
import com.axonect.ee.enterpriseintegration.domain.mapper.EventMapper;
import com.axonect.ee.enterpriseintegration.domain.repository.DownloadReportRepository;
import com.axonect.ee.enterpriseintegration.domain.service.DownloadReportService;
import com.axonect.ee.enterpriseintegration.domain.service.UnifiedReportDownloadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class DownloadReportServiceImpl implements DownloadReportService {

    private final DownloadReportRepository downloadReportRepository;
    private final DownloadReportMapper downloadReportMapper;
    private final UnifiedReportDownloadService unifiedReportDownloadService;
    private final Executor reportDispatchExecutor;

    @Value("${report.output.directory}")
    String reportOutputDirectory;

    public DownloadReportServiceImpl(DownloadReportRepository downloadReportRepository, DownloadReportMapper downloadReportMapper, UnifiedReportDownloadService unifiedReportDownloadService, Executor reportDispatchExecutor) {
        this.downloadReportRepository = downloadReportRepository;
        this.downloadReportMapper = downloadReportMapper;
        this.unifiedReportDownloadService = unifiedReportDownloadService;
        this.reportDispatchExecutor = reportDispatchExecutor;
    }

    @Override
    public CommonAdaptorResp<Void> createDownloadReportRequest(DownloadReportRequest downloadReportRequest) throws BaseException {
        long startTime = System.currentTimeMillis();
        try {
            log.info("Creating Download Report: {}", downloadReportRequest);
            DownloadReport downloadReport = downloadReportMapper.mapDownloadRequestToEntity(downloadReportRequest);

            // Start as Pending — UnifiedReportDownloadService will promote to Processing
            downloadReport.setReportStatus("Pending");

            DownloadReport savedReport = downloadReportRepository.save(downloadReport);
            log.info(LoggingAdviceConstants.REPORT_SAVED_TO_DB, savedReport.getId());

            String reportId = String.valueOf(savedReport.getId());

            log.info(LoggingAdviceConstants.REPORT_SENT_TO_ASYNC, reportId);
            CompletableFuture.runAsync(
                    () -> unifiedReportDownloadService.processReport(reportId),
                    reportDispatchExecutor
            );

            log.info(LoggingAdviceConstants.REQUEST_TERMINATED, System.currentTimeMillis() - startTime, "Success");
            return new CommonAdaptorResp<>(true, ResponseCodeEnum.CREATE_SUCCESS.message());

        } catch (BaseException ex) {
            // A rejected request — an unknown format, or a spreadsheet asked of a CSV-only
            // report. Its own code and message are the useful answer, so let it through.
            log.warn("Rejected download report request: {}", ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error(String.valueOf(ResponseCodeEnum.LOG_NOT_CONNECTED), System.currentTimeMillis() - startTime,
                    ex.getMessage(), StackTraceTracker.displayStackStraceArray(ex.getStackTrace()));
            throw new BaseException(ResponseCodeEnum.LOG_NOT_CONNECTED.code(), ResponseCodeEnum.LOG_NOT_CONNECTED.message());
        }
    }

    @Override
    public Resource getReportFile(Long reportId, HttpServletRequest httpServletRequest ) throws BaseException {
        long startTime = System.currentTimeMillis();
        log.info("Getting report file for report ID: {}", reportId);

        try {
            // First check if the report exists in the database
            Optional<DownloadReport> reportOptional = downloadReportRepository.findById(reportId);

            if (reportOptional.isEmpty()) {
                log.warn("Report not found with ID: {}", reportId);
                throw new BaseException(ResponseCodeEnum.REPORT_NOT_FOUND.code(), ResponseCodeEnum.REPORT_NOT_FOUND.message());
            }

            DownloadReport report = reportOptional.get();

            // Find the report file located in the path
            File reportFile = findReportFile(reportId);
            if (reportFile == null || !reportFile.exists()) {
                log.warn("Report file not found for report ID: {}", reportId);
                throw new BaseException(ResponseCodeEnum.REPORT_FILE_NOT_FOUND.code(),
                        "Report file not found. Current status: " + report.getReportStatus());
            }
                // Update report status if needed
                if (!"Completed".equals(report.getReportStatus())) {
                    report.setReportStatus("Completed");
                    report.setLastUpdatedAt(new Date());
                    downloadReportRepository.save(report);
                }

            log.info("Report file stream prepared in {}ms. File size: {} bytes",
                    System.currentTimeMillis() - startTime, reportFile.length());

            return new FileSystemResource(reportFile);


        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error(LoggingAdviceConstants.EXCEPTION_STACK_TRACE, System.currentTimeMillis() - startTime,
                    e.getMessage(), StackTraceTracker.displayStackStraceArray(e.getStackTrace()));
            throw new BaseException(ResponseCodeEnum.INTERNAL_SERVER_ERROR.code(), ResponseCodeEnum.INTERNAL_SERVER_ERROR.message());
        }
    }

    @Override
    public String getReportFilename(Long reportId) throws BaseException {
        File reportFile = findReportFile(reportId);
        if (reportFile == null || !reportFile.exists()) {
            throw new BaseException(ResponseCodeEnum.REPORT_FILE_NOT_FOUND.code(), ResponseCodeEnum.REPORT_FILE_NOT_FOUND.message());
        }
        return reportFile.getName();
    }

    File findReportFile(Long reportId) throws BaseException {
        try {
            // Ensure directory exists
            File directory = new File(reportOutputDirectory);
            if (!directory.exists() || !directory.isDirectory()) {
                log.error("Report directory does not exist or is not a directory: {}", reportOutputDirectory);
                throw new BaseException(ResponseCodeEnum.REPORT_DIR_ERROR.code(),
                        "Report directory not found: " + reportOutputDirectory);
            }

            // Look for files with the pattern: {reportId}_*.csv
            String filePrefix = reportId + "_";
            File[] matchingFiles = directory.listFiles(file ->
                    file.isFile() &&
                            file.getName().startsWith(filePrefix) );

            if (matchingFiles == null || matchingFiles.length == 0) {
                log.warn("No matching report files found for report ID: {}", reportId);
                return null;
            }

            // If multiple files found, return the most recent one (by last modified time)
            if (matchingFiles.length > 1) {
                return Arrays.stream(matchingFiles)
                        .max(Comparator.comparingLong(File::lastModified))
                        .orElse(null);
            }

            return matchingFiles[0];

        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error finding report file for ID {}: {}", reportId, e.getMessage(), e);
            throw new BaseException(ResponseCodeEnum.REPORT_DIR_ERROR.code(),
                    "Error accessing report directory: " + e.getMessage());
        }
    }



}

