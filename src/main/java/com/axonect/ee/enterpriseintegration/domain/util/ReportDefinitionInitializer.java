package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.domain.service.impl.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class ReportDefinitionInitializer {

    private final MessageLogReportDefinition messageLogDefinition;
    private final SubscriberDetailsReportDefinition subscriberDetailsReportDefinition;
    private final ProductDetailsReportDefinition productDetailsReportDefinition;
    private final SessionHistoryReportDefinition sessionHistoryReportDefinition;
    private final ErrorLogReportDefinition errorLogReportDefinition;
    private final AuditLogReportDefinition auditLogReportDefinition;

    public ReportDefinitionInitializer(MessageLogReportDefinition messageLogDefinition,
                                       SessionHistoryReportDefinition sessionHistoryReportDefinition,
                                       ProductDetailsReportDefinition productDetailsReportDefinition,
                                       SubscriberDetailsReportDefinition subscriberDetailsReportDefinition,
                                       ErrorLogReportDefinition errorLogReportDefinition,
                                       AuditLogReportDefinition auditLogReportDefinition) {

        this.messageLogDefinition = messageLogDefinition;
        this.productDetailsReportDefinition = productDetailsReportDefinition;
        this.sessionHistoryReportDefinition = sessionHistoryReportDefinition;
        this.subscriberDetailsReportDefinition = subscriberDetailsReportDefinition;
        this.errorLogReportDefinition = errorLogReportDefinition;
        this.auditLogReportDefinition = auditLogReportDefinition;
    }

    @PostConstruct
    public void init() {
        ReportDefinitionsRegistry.register("MESSAGE_LOGS", messageLogDefinition);
        ReportDefinitionsRegistry.register("SUBSCRIBER_DETAILS", subscriberDetailsReportDefinition);
        ReportDefinitionsRegistry.register("SESSION_HISTORY", sessionHistoryReportDefinition);
        ReportDefinitionsRegistry.register("PRODUCT_DETAILS", productDetailsReportDefinition);
        ReportDefinitionsRegistry.register("ERROR_LOGS", errorLogReportDefinition);
        ReportDefinitionsRegistry.register("AUDIT_LOGS", auditLogReportDefinition);
    }
}
