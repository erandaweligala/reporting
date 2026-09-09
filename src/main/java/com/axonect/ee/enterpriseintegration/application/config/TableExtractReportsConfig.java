package com.axonect.ee.enterpriseintegration.application.config;

import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtracts;
import com.axonect.ee.enterpriseintegration.domain.repository.StreamingRowReader;
import com.axonect.ee.enterpriseintegration.domain.service.impl.TableExtractReportDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One report definition per table extract.
 *
 * <p>The three extracts differ only in the {@link Spec} they carry, so they are declared here as
 * beans over the shared definition rather than as three near-identical classes.
 * {@code ReportDefinitionInitializer} injects them as a list and registers each under its own
 * {@code reportType()}, so a fourth extract is a spec in {@link TableExtracts} and a bean here.
 */
@Configuration
public class TableExtractReportsConfig {

    @Bean
    public TableExtractReportDefinition macServiceTableReportDefinition(
            StreamingRowReader rowReader, TableExtractProperties properties) {
        return new TableExtractReportDefinition(TableExtracts.MAC_SERVICE_TABLE, rowReader, properties);
    }

    @Bean
    public TableExtractReportDefinition planToBucketReportDefinition(
            StreamingRowReader rowReader, TableExtractProperties properties) {
        return new TableExtractReportDefinition(TableExtracts.PLAN_TO_BUCKET, rowReader, properties);
    }

    @Bean
    public TableExtractReportDefinition bucketInstanceReportDefinition(
            StreamingRowReader rowReader, TableExtractProperties properties) {
        return new TableExtractReportDefinition(TableExtracts.BUCKET_INSTANCE, rowReader, properties);
    }
}
