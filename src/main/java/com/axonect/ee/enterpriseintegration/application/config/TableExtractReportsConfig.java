package com.axonect.ee.enterpriseintegration.application.config;

import com.axonect.ee.enterpriseintegration.domain.client.UsageAggregationClient;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtracts;
import com.axonect.ee.enterpriseintegration.domain.repository.StreamingRowReader;
import com.axonect.ee.enterpriseintegration.domain.service.impl.BucketUsageColumnSource;
import com.axonect.ee.enterpriseintegration.domain.service.impl.TableExtractReportDefinition;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    /**
     * The bucket extract, with USAGE read either from the CDR session documents in Elasticsearch or
     * from the table's own column — see {@link TableExtractProperties#isUsageFromCdr()}.
     *
     * <p>Which one it is decides the whole shape of the run, not just one column's source: the CDR
     * figure is keyed on the username holding each bucket's service, so that variant joins
     * SERVICE_INSTANCE for the username, orders its rows by it and walks the aggregation in step,
     * while the other is the single unordered scan the other two extracts are. That is why the two
     * are separate specs rather than one spec and a flag, and why the choice is made once here
     * rather than per run.
     */
    @Bean
    public TableExtractReportDefinition bucketInstanceReportDefinition(
            StreamingRowReader rowReader,
            TableExtractProperties properties,
            UserDumpProperties usageProperties,
            UsageAggregationClient usageAggregationClient) {

        if (!usageFromCdr(properties, usageProperties.getUsage())) {
            return new TableExtractReportDefinition(TableExtracts.BUCKET_INSTANCE, rowReader, properties);
        }

        Spec spec = TableExtracts.BUCKET_INSTANCE_FROM_CDR;
        boolean scopeToService = usageProperties.getUsage().isScopeToService();

        // A cursor per run, opened when the extract starts and closed with it. The extract is not
        // sharded, so the one cursor covers the whole username keyspace.
        return new TableExtractReportDefinition(spec, rowReader, properties,
                () -> new BucketUsageColumnSource(
                        spec, usageAggregationClient.openBucketTotals(null, null), scopeToService));
    }

    /**
     * Whether BUCKET_INSTANCE.USAGE will be the CDR total, and why if not.
     *
     * <p>Two things besides the switch itself can take it away, and both of them would otherwise
     * produce a column that looks right and is not:
     *
     * <ul>
     *   <li>An Elasticsearch lookup that is switched off returns no figures at all, which would
     *       empty the column for every row rather than fail the run.</li>
     *   <li>A {@code sessionInstances} array that is not mapped as nested cannot be summed per
     *       bucket — Elasticsearch flattens it, so the only figure available is the subscriber's
     *       whole usage, and writing that against each of their buckets would read as a total
     *       several times over. Fixing the mapping is what gets the column back.</li>
     * </ul>
     *
     * <p>Either way the extract keeps reporting the table's own counter, and says so in the startup
     * log — the file itself cannot show which of the two figures it carries.
     */
    private boolean usageFromCdr(TableExtractProperties properties, UserDumpProperties.Usage usage) {
        String reason = null;
        if (!properties.isUsageFromCdr()) {
            reason = "report.table-extract.usage-from-cdr is off";
        } else if (!usage.isEnabled()) {
            reason = "report.user-dump.usage.enabled is off, so there are no CDR figures to read";
        } else if (!usage.isNested()) {
            reason = "report.user-dump.usage.nested is off, so CDR usage cannot be attributed to "
                    + "one bucket";
        }

        if (reason != null) {
            log.info("BUCKET_INSTANCE.USAGE reports the table's own counter: {}", reason);
            return false;
        }
        log.info("BUCKET_INSTANCE.USAGE reports the CDR total from the {} indices, {} to the "
                        + "bundle each bucket belongs to — the figure USER_DATA_DUMP reports as "
                        + "UTLIZED_QUOTA",
                usage.getIndex(), usage.isScopeToService() ? "scoped" : "unscoped");
        return true;
    }
}
