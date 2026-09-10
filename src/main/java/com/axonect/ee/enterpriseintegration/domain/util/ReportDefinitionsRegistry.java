package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import com.axonect.ee.enterpriseintegration.domain.service.StreamingReportDefinition;

import java.util.HashMap;
import java.util.Map;

public class ReportDefinitionsRegistry {
    private static final Map<String, ReportDefinition> registry = new HashMap<>();

    public static void register(String reportType, ReportDefinition definition) {
        registry.put(reportType, definition);
    }

    /**
     * Whether the report type writes itself off a cursor rather than through the paged loop.
     * Such a report is produced as CSV only, so the format has to be settled before the request
     * is accepted. Unknown types answer {@code false} — {@link #getDefinition} is where an
     * unregistered type is reported.
     */
    public static boolean isStreaming(String reportType) {
        return registry.get(reportType) instanceof StreamingReportDefinition;
    }

    public static ReportDefinition getDefinition(String reportType) {
        if (!registry.containsKey(reportType)) {
            throw new IllegalArgumentException("No definition registered for report type: " + reportType);
        }
        return registry.get(reportType);
    }
}
