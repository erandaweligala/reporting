package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.config.ScheduledDumpProperties;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import com.axonect.ee.enterpriseintegration.domain.service.DownloadReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One firing of the scheduled dumps: a report request per report type, filed the way the create
 * endpoint files one.
 *
 * <p>It only requests the dumps; it does not run them. Each request is saved as Pending and handed
 * to the same dispatcher a manual request goes to, so the dumps take their turn under the same
 * concurrency cap, are watched by the same watchdog and land in the same directory. That also keeps
 * the firing itself short — a few inserts — which matters because it runs on the scheduler thread
 * the watchdog shares.
 *
 * <p>Report types are requested independently: one that cannot be requested is logged and the rest
 * are still requested, rather than a bad night for one file costing all four.
 */
@Component
@Slf4j
public class ScheduledDumpJob {

    private final DownloadReportService downloadReportService;
    private final ScheduledDumpProperties properties;

    public ScheduledDumpJob(DownloadReportService downloadReportService, ScheduledDumpProperties properties) {
        this.downloadReportService = downloadReportService;
        this.properties = properties;
    }

    public void run(List<String> reportTypes) {
        log.info("Scheduled dumps: requesting {} as {}", reportTypes, properties.getCreatedBy());

        int requested = 0;
        for (String reportType : reportTypes) {
            if (request(reportType)) {
                requested++;
            }
        }

        if (requested == reportTypes.size()) {
            log.info("Scheduled dumps: {} report(s) requested", requested);
        } else {
            log.warn("Scheduled dumps: {} of {} report(s) requested; see the errors above for the rest",
                    requested, reportTypes.size());
        }
    }

    private boolean request(String reportType) {
        DownloadReportRequest request = new DownloadReportRequest();
        request.setCreatedBy(properties.getCreatedBy());
        request.setReportType(reportType);
        // Format is left to the report type, as it is for a create request that names none: the
        // dumps are streaming reports and resolve to CSV, the only format they can be written in.

        try {
            downloadReportService.createDownloadReportRequest(request);
            return true;
        } catch (Exception e) {
            log.error("Scheduled dumps: could not request {}", reportType, e);
            return false;
        }
    }
}
