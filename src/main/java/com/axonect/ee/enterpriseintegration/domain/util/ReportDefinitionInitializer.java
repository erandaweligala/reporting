package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.domain.service.impl.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReportDefinitionInitializer {

    private final MessageLogReportDefinition messageLogDefinition;
    private final SubscriberDetailsReportDefinition subscriberDetailsReportDefinition;
    private final ProductDetailsReportDefinition productDetailsReportDefinition;
    private final SessionHistoryReportDefinition sessionHistoryReportDefinition;
    private final ErrorLogReportDefinition errorLogReportDefinition;
    private final AuditLogReportDefinition auditLogReportDefinition;
    private final UserDataDumpReportDefinition userDataDumpReportDefinition;
    private final List<TableExtractReportDefinition> tableExtractReportDefinitions;

    public ReportDefinitionInitializer(MessageLogReportDefinition messageLogDefinition,
                                       SessionHistoryReportDefinition sessionHistoryReportDefinition,
                                       ProductDetailsReportDefinition productDetailsReportDefinition,
                                       SubscriberDetailsReportDefinition subscriberDetailsReportDefinition,
                                       ErrorLogReportDefinition errorLogReportDefinition,
                                       AuditLogReportDefinition auditLogReportDefinition,
                                       UserDataDumpReportDefinition userDataDumpReportDefinition,
                                       List<TableExtractReportDefinition> tableExtractReportDefinitions) {

        this.messageLogDefinition = messageLogDefinition;
        this.productDetailsReportDefinition = productDetailsReportDefinition;
        this.sessionHistoryReportDefinition = sessionHistoryReportDefinition;
        this.subscriberDetailsReportDefinition = subscriberDetailsReportDefinition;
        this.errorLogReportDefinition = errorLogReportDefinition;
        this.auditLogReportDefinition = auditLogReportDefinition;
        this.userDataDumpReportDefinition = userDataDumpReportDefinition;
        this.tableExtractReportDefinitions = tableExtractReportDefinitions;
    }

    @PostConstruct
    public void init() {
        ReportDefinitionsRegistry.register("MESSAGE_LOGS", messageLogDefinition);
        ReportDefinitionsRegistry.register("SUBSCRIBER_DETAILS", subscriberDetailsReportDefinition);
        ReportDefinitionsRegistry.register("SESSION_HISTORY", sessionHistoryReportDefinition);
        ReportDefinitionsRegistry.register("PRODUCT_DETAILS", productDetailsReportDefinition);
        ReportDefinitionsRegistry.register("ERROR_LOGS", errorLogReportDefinition);
        ReportDefinitionsRegistry.register("AUDIT_LOGS", auditLogReportDefinition);
        ReportDefinitionsRegistry.register(UserDataDumpReportDefinition.REPORT_TYPE, userDataDumpReportDefinition);

        // The table extracts already know what they are called, so they are registered from their
        // own report type rather than from a list repeated here — adding one is a bean, not an
        // edit in two places that can disagree.
        tableExtractReportDefinitions.forEach(
                definition -> ReportDefinitionsRegistry.register(definition.reportType(), definition));
    }
}
