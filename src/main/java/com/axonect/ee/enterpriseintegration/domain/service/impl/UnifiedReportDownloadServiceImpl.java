package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.application.util.exception.StackTraceTracker;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.repository.DownloadReportRepository;
import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import com.axonect.ee.enterpriseintegration.domain.service.ReportWriter;
import com.axonect.ee.enterpriseintegration.domain.service.StreamingReportDefinition;
import com.axonect.ee.enterpriseintegration.domain.service.UnifiedReportDownloadService;
import com.axonect.ee.enterpriseintegration.domain.util.ReportDefinitionsRegistry;
import com.axonect.ee.enterpriseintegration.domain.util.ReportFormat;
import com.axonect.ee.enterpriseintegration.domain.util.ReportWriterFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class UnifiedReportDownloadServiceImpl implements UnifiedReportDownloadService {

    private static final int MAX_CONCURRENT_REPORTS = 5;

    private final DownloadReportRepository downloadReportRepository;
    private final ReportWriterFactory reportWriterFactory;
    private final ApplicationContext applicationContext;

    @Value("${report.default.offset}")
    Integer defaultOffset;

    @Value("${report.batch.size}")
    Integer batchSize;

    @Value("${report.output.directory}")
    String reportOutputDirectory;

    @Value("${report.watchdog.stale-threshold-minutes}")
    private long staleThresholdMinutes;

    public UnifiedReportDownloadServiceImpl(
            DownloadReportRepository downloadReportRepository,
            ReportWriterFactory reportWriterFactory,
            ApplicationContext applicationContext
            ) {
        this.downloadReportRepository = downloadReportRepository;
        this.reportWriterFactory = reportWriterFactory;
        this.applicationContext = applicationContext;
    }

    @Scheduled(fixedRateString = "${report.watchdog.check-interval-ms}")
    @Transactional
    public void reapStaleProcessingReports() {
        long staleThresholdMs = staleThresholdMinutes * 60 * 1000L;
        Date cutoff = new Date(System.currentTimeMillis() - staleThresholdMs);
        List<DownloadReport> staleReports = downloadReportRepository.findStaleProcessingReports(cutoff);

        if (staleReports.isEmpty()) {
            return;
        }

        log.warn("Found {} stale Processing report(s) exceeding {} min threshold. Marking as Failed.",
                staleReports.size(), staleThresholdMinutes);

        for (DownloadReport report : staleReports) {
            report.setReportStatus("Failed");
            report.setLastUpdatedAt(new Date());
            downloadReportRepository.save(report);
            log.warn("Report ID {} marked Failed by watchdog — stuck in Processing since {}",
                    report.getId(), report.getLastUpdatedAt());
        }

        for (int i = 0; i < staleReports.size(); i++) {
            pickNextPendingReport();
        }
    }
    /**
     * Entry point — called from DownloadReportServiceImpl.
     * Tries to grab a slot; if full, leaves report as Pending.
     */
    @Override
    public void processReport(String reportId) {
        boolean dispatched = tryAcquireSlotAndProcess(reportId);
        if (!dispatched) {
            log.info("Report ID {} queued as Pending — max concurrent limit ({}) reached",
                    reportId, MAX_CONCURRENT_REPORTS);
        }
    }

    /**
     * Checks DB count and dispatches if a slot is free.
     * Synchronized to prevent race condition on the count check.
     */
    @Transactional
    public synchronized boolean tryAcquireSlotAndProcess(String reportId) {
        long processingCount = downloadReportRepository.countProcessingReports();

        if (processingCount >= MAX_CONCURRENT_REPORTS) {
            setReportStatus(Long.parseLong(reportId), "Pending");
            return false;
        }

        setReportStatus(Long.parseLong(reportId), "Processing");
        // Invoke via Spring proxy so @Async is honored (self-invocation bypasses proxy)
        applicationContext.getBean(UnifiedReportDownloadServiceImpl.class).executeReportAsync(reportId);
        return true;
    }

    /**
     * Runs on the capped thread pool. When done, picks up the next Pending report.
     */
    @Async("reportExecutor")
    public void executeReportAsync(String reportId) {
        long startTime = System.currentTimeMillis();
        log.info("Report generation started for ID: {}", reportId);

        try {
            Long reportIdLong = Long.parseLong(reportId);
            Optional<DownloadReport> reportOptional = downloadReportRepository.findById(reportIdLong);

            if (reportOptional.isEmpty()) {
                log.error("Report ID {} not found in database", reportId);
                return;
            }

            DownloadReport report = reportOptional.get();
            ReportDefinition reportDefinition = ReportDefinitionsRegistry.getDefinition(report.getReportType());
            String filePath = reportDefinition instanceof StreamingReportDefinition streamingDefinition
                    ? processStreamingReport(report, streamingDefinition)
                    : processStandardReport(report, reportDefinition);

            if (filePath == null) {
                throw new IllegalStateException("No data found for report ID: " + reportId);
            }

            log.info("Successfully processed report ID: {}. Output file: {}", reportId, filePath);

        } catch (NumberFormatException e) {
            log.error("Invalid report ID format: {}", reportId, e);
        } catch (Exception e) {
            handleReportError(reportId, e);
        } finally {
            log.info("Report processing for ID {} took {} ms", reportId,
                    System.currentTimeMillis() - startTime);
            pickNextPendingReport();
        }
    }

    /**
     * Called at the end of each report — picks the oldest Pending report and dispatches it.
     */
    private void pickNextPendingReport() {
        downloadReportRepository
                .findFirstByReportStatusOrderByCreatedAtAsc("Pending")
                .ifPresent(next -> {
                    log.info("Picking up next pending report ID: {}", next.getId());
                    tryAcquireSlotAndProcess(String.valueOf(next.getId()));
                });
    }

    private void setReportStatus(Long reportId, String status) {
        downloadReportRepository.findById(reportId).ifPresent(report -> {
            report.setReportStatus(status);
            report.setLastUpdatedAt(new Date());
            downloadReportRepository.save(report);
        });
    }

    private void handleReportError(String reportId, Exception e) {
        String stackTrace = StackTraceTracker.displayStackStraceArray(e.getStackTrace());
        log.error("Error processing report ID: {}. StackTrace: {}", reportId, stackTrace, e);

        try {
            Long reportIdLong = Long.parseLong(reportId);
            downloadReportRepository.findById(reportIdLong).ifPresent(report -> {
                if (!"No Records".equals(report.getReportStatus())) {
                    report.setReportStatus("Failed");
                    report.setLastUpdatedAt(new Date());
                    downloadReportRepository.save(report);
                }
            });
        } catch (Exception ex) {
            log.error("Failed to update report status after error for report ID: {}", reportId, ex);
        }
    }

    /**
     * Runs a report that writes itself.
     *
     * <p>The paged loop below cannot serve an extract of a few million rows: it asks for rows at
     * an ever-growing offset and materialises each page before any of it reaches the file. A
     * streaming definition instead keeps one cursor open and appends rows as they arrive, so this
     * method only sets up the file, hands it over and records the outcome.
     */
    private String processStreamingReport(DownloadReport report, StreamingReportDefinition definition)
            throws Exception {

        ReportFormat format = ReportFormat.valueOf(report.getFormat());
        if (format != ReportFormat.CSV) {
            throw new IllegalArgumentException(definition.reportType()
                    + " is produced as CSV only; a spreadsheet cannot hold a report of this size. "
                    + "Requested format: " + format);
        }

        ReportWriter writer = reportWriterFactory.get(format);
        Path outputPath = writer.init(
                report.getId() + "_" + definition.reportType(),
                definition.columns(),
                report.getClassificationLevel() != null ? report.getClassificationLevel() : "Open");

        long startTime = System.currentTimeMillis();
        long rows = definition.streamTo(report, outputPath);

        if (rows == 0) {
            log.warn("No records found for report ID: {}", report.getId());
            Files.deleteIfExists(outputPath);
            updateReportStatus(report, "No Records", null);
            return null;
        }

        log.info("Completed streaming report. Report ID: {} | Type: {} | Rows: {} | Took: {} ms",
                report.getId(), definition.reportType(), rows, System.currentTimeMillis() - startTime);

        updateReportStatus(report, "Completed", outputPath.toString());
        return outputPath.toString();
    }

    private String processStandardReport(DownloadReport report, ReportDefinition reportDefinition) throws IOException {
        ReportFormat format = ReportFormat.valueOf(report.getFormat());
        ReportWriter writer = reportWriterFactory.get(format);

        Path outputPath = null;
        int offset = defaultOffset;
        int batchNo = 1;
        int totalProcessed = 0;

        while (true) {
            try {
                CommonAdaptorResp<FilterListViewBatch> response =
                        reportDefinition.fetchBatch(report, offset, batchSize);

                if (response == null || response.getData() == null ||
                        response.getData().getTableData() == null ||
                        response.getData().getTableData().isEmpty()) {
                    log.info("No more data for report ID: {}. Total processed: {}", report.getId(), totalProcessed);
                    break;
                }

                FilterListViewBatch batchData = response.getData();
                List<Map<String, Object>> rows = batchData.getTableData();
                int fetched = rows.size();

                if (outputPath == null) {
                    outputPath = writer.init(report.getId() + "_" + reportDefinition.reportType(),
                            reportDefinition.columns(),
                            report.getClassificationLevel() != null ? report.getClassificationLevel() : "Open");
                }

                writer.writeBatch(outputPath, reportDefinition.columns(), rows);

                totalProcessed += fetched;
                offset += fetched;

                log.info("Report ID: {} | Type: {} | Batch: {} | Records: {} | Total: {}",
                        report.getId(), reportDefinition.reportType(), batchNo++, fetched, totalProcessed);

                // Last page — fetched fewer rows than requested, nothing more to fetch
                if (fetched < batchSize) {
                    log.info("Last batch reached for report ID: {} — fetched {} < batchSize {}",
                            report.getId(), fetched, batchSize);
                    break;
                }

            } catch (Exception e) {
                log.error("Error processing report {} at offset {}", report.getId(), offset, e);
                break;
            }
        }

        if (outputPath != null && writer instanceof ExcelReportWriter) {
            ((ExcelReportWriter) writer).close(outputPath);
        }

        if (outputPath == null) {
            log.warn("No records found for report ID: {}", report.getId());
            updateReportStatus(report, "No Records", null);
            return null;
        }

        log.info("Completed report generation. Report ID: {} | Format: {} | Total Records: {}",
                report.getId(), format, totalProcessed);

        updateReportStatus(report, "Completed", String.valueOf(outputPath));
        return outputPath.toString();
    }

    private void updateReportStatus(DownloadReport report, String status, String outputPath) {
        try {
            report.setReportStatus(status);
            report.setLastUpdatedAt(new Date());
            report.setReportName(outputPath);
            downloadReportRepository.save(report);
        } catch (Exception e) {
            log.error("Failed to update report status: {}", status, e);
        }
    }
}

