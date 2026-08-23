package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.domain.service.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReportDefinitionInitializerTest {

    private MessageLogReportDefinition messageLog;
    private SubscriberDetailsReportDefinition subscriber;
    private ProductDetailsReportDefinition product;
    private SessionHistoryReportDefinition session;
    private ErrorLogReportDefinition error;
    private AuditLogReportDefinition audit;

    private ReportDefinitionInitializer initializer;

    @BeforeEach
    void setUp() {
        messageLog = mock(MessageLogReportDefinition.class);
        subscriber = mock(SubscriberDetailsReportDefinition.class);
        product = mock(ProductDetailsReportDefinition.class);
        session = mock(SessionHistoryReportDefinition.class);
        error = mock(ErrorLogReportDefinition.class);
        audit = mock(AuditLogReportDefinition.class);

        initializer = new ReportDefinitionInitializer(
                messageLog,
                session,
                product,
                subscriber,
                error,
                audit
        );

    }

    @Test
    void init_shouldRegisterAllReportDefinitions() {
        initializer.init();

        assertSame(messageLog,
                ReportDefinitionsRegistry.getDefinition("MESSAGE_LOGS"));

        assertSame(subscriber,
                ReportDefinitionsRegistry.getDefinition("SUBSCRIBER_DETAILS"));

        assertSame(session,
                ReportDefinitionsRegistry.getDefinition("SESSION_HISTORY"));

        assertSame(product,
                ReportDefinitionsRegistry.getDefinition("PRODUCT_DETAILS"));

        assertSame(error,
                ReportDefinitionsRegistry.getDefinition("ERROR_LOGS"));
    }
}
