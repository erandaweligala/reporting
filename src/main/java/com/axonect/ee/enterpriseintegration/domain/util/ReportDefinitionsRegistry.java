package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;

import java.util.HashMap;
import java.util.Map;

public class ReportDefinitionsRegistry {
    private static final Map<String, ReportDefinition> registry = new HashMap<>();

    public static void register(String reportType, ReportDefinition definition) {
        registry.put(reportType, definition);
    }

    public static ReportDefinition getDefinition(String reportType) {
        if (!registry.containsKey(reportType)) {
            throw new IllegalArgumentException("No definition registered for report type: " + reportType);
        }
        return registry.get(reportType);
    }
}
