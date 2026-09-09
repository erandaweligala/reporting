package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.application.config.TableExtractProperties;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtracts;
import com.axonect.ee.enterpriseintegration.domain.service.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReportDefinitionInitializerTest {

    private MessageLogReportDefinition messageLog;
    private SubscriberDetailsReportDefinition subscriber;
    private ProductDetailsReportDefinition product;
    private SessionHistoryReportDefinition session;
    private ErrorLogReportDefinition error;
    private AuditLogReportDefinition audit;
    private UserDataDumpReportDefinition userDataDump;
    private List<TableExtractReportDefinition> tableExtracts;

    private ReportDefinitionInitializer initializer;

    @BeforeEach
    void setUp() {
        messageLog = mock(MessageLogReportDefinition.class);
        subscriber = mock(SubscriberDetailsReportDefinition.class);
        product = mock(ProductDetailsReportDefinition.class);
        session = mock(SessionHistoryReportDefinition.class);
        error = mock(ErrorLogReportDefinition.class);
        audit = mock(AuditLogReportDefinition.class);
        userDataDump = mock(UserDataDumpReportDefinition.class);
        tableExtracts = TableExtracts.ALL.stream()
                .map(spec -> new TableExtractReportDefinition(spec, null, new TableExtractProperties()))
                .toList();

        initializer = new ReportDefinitionInitializer(
                messageLog,
                session,
                product,
                subscriber,
                error,
                audit,
                userDataDump,
                tableExtracts
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

        assertSame(audit,
                ReportDefinitionsRegistry.getDefinition("AUDIT_LOGS"));

        assertSame(userDataDump,
                ReportDefinitionsRegistry.getDefinition(UserDataDumpReportDefinition.REPORT_TYPE));
    }

    @Test
    void init_shouldRegisterEveryTableExtractUnderItsOwnReportType() {
        initializer.init();

        for (TableExtractReportDefinition extract : tableExtracts) {
            assertSame(extract, ReportDefinitionsRegistry.getDefinition(extract.reportType()));
        }

        // Named explicitly as well: the three report types are what callers ask for, so a spec
        // renamed by accident should fail here and not only at the first request for it.
        assertEquals(
                List.of(TableExtracts.MAC_SERVICE_TABLE_TYPE,
                        TableExtracts.PLAN_TO_BUCKET_TYPE,
                        TableExtracts.BUCKET_INSTANCE_TYPE),
                tableExtracts.stream().map(TableExtractReportDefinition::reportType).toList());
    }
}
