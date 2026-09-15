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
     *
     * <p>Both are built around the same bucket type for RULE, which is the one the user data dump
     * reads PLAN_BANDWIDTH under: the column reports the same figure whichever variant runs, since
     * where USAGE comes from is not something the consuming system can see in the file.
     */
    @Bean
    public TableExtractReportDefinition bucketInstanceReportDefinition(
            StreamingRowReader rowReader,
            TableExtractProperties properties,
            UserDumpProperties usageProperties,
            UsageAggregationClient usageAggregationClient) {

        String bandwidthBucketType = ruleFromPlanBandwidth(usageProperties);

        if (!usageFromCdr(properties, usageProperties)) {
            return new TableExtractReportDefinition(
                    TableExtracts.bucketInstance(bandwidthBucketType), rowReader, properties);
        }

        Spec spec = TableExtracts.bucketInstanceFromCdr(bandwidthBucketType);
        boolean scopeToService = usageProperties.getUsage().isScopeToService();
        String quotaBucketType = usageProperties.getQuotaBucketType();

        // A cursor per run, opened when the extract starts and closed with it. The extract is not
        // sharded, so the one cursor covers the whole username keyspace.
        return new TableExtractReportDefinition(spec, rowReader, properties,
                () -> new BucketUsageColumnSource(
                        spec, usageAggregationClient.openBucketTotals(null, null), scopeToService,
                        quotaBucketType));
    }

    /**
     * The bucket type BUCKET_INSTANCE.RULE is reported from, and a line saying so.
     *
     * <p>RULE is the id of the bandwidth bucket held by the bundle each row belongs to — what the
     * user data dump reports for that bundle as PLAN_BANDWIDTH — rather than the table's own RULE
     * column. Which bucket type that is comes from the dump's own setting, so the two reports
     * cannot be pointed at different types, and an empty setting leaves both columns empty rather
     * than one of them.
     *
     * <p>Like USAGE, nothing in the file says where the column came from, so the run says it here.
     */
    private String ruleFromPlanBandwidth(UserDumpProperties dump) {
        String bandwidthBucketType = dump.getBandwidthBucketType();
        log.info("BUCKET_INSTANCE.RULE reports the {} bucket of the bundle each row belongs to — "
                        + "the figure USER_DATA_DUMP reports as PLAN_BANDWIDTH — and not the "
                        + "table's own RULE column",
                bandwidthBucketType);
        return bandwidthBucketType;
    }

    /**
     * Whether BUCKET_INSTANCE.USAGE will be the CDR total, and why if not.
     *
     * <p>The rule is that this column reports whatever UTLIZED_QUOTA reports, so the two files can
     * be reconciled row for row. It follows that the only thing that can take the CDR figure away
     * from this extract is the thing that takes it away from the dump as well: an Elasticsearch
     * lookup that is switched off, which has no figures for either report.
     *
     * <p>A {@code sessionInstances} array that is not mapped as nested used to take it away too,
     * because Elasticsearch flattens such an array and the only figure it can then give back is the
     * subscriber's whole usage — which against each of their buckets would read as that total
     * several times over. Falling back to the table's own counter for it is what left USAGE at 0
     * beside a UTLIZED_QUOTA that was reporting the CDR total for the very same subscriber. The
     * extract now reports that total the way the dump does and writes it against one bucket rather
     * than all of them, so the mapping no longer decides which of two unrelated figures the column
     * carries — only how exact it is. See {@link BucketUsageColumnSource}.
     *
     * <p>Which of the two a run reports is in the startup log — the file itself cannot show it.
     */
    private boolean usageFromCdr(TableExtractProperties properties, UserDumpProperties dump) {
        UserDumpProperties.Usage usage = dump.getUsage();
        String reason = null;
        if (!properties.isUsageFromCdr()) {
            reason = "report.table-extract.usage-from-cdr is off";
        } else if (!usage.isEnabled()) {
            reason = "report.user-dump.usage.enabled is off, so there are no CDR figures to read";
        }

        if (reason != null) {
            log.info("BUCKET_INSTANCE.USAGE reports the table's own counter: {}", reason);
            return false;
        }
        log.info("BUCKET_INSTANCE.USAGE reports the CDR total from the {} indices, {} to the "
                        + "bundle each bucket belongs to — the figure USER_DATA_DUMP reports as "
                        + "UTLIZED_QUOTA",
                usage.getIndex(), usage.isScopeToService() ? "scoped" : "unscoped");
        if (!usage.isNestedTable()) {
            log.info("BUCKET_INSTANCE.USAGE cannot be split per bucket: "
                            + "report.user-dump.usage.nested is off, so each subscriber's whole "
                            + "CDR total — the figure USER_DATA_DUMP reports for them as "
                            + "UTLIZED_QUOTA — is reported against their {} bucket and their other "
                            + "buckets report 0. Mapping sessionInstances as nested is what makes "
                            + "the column per-bucket again",
                    dump.getQuotaBucketType());
        }
        return true;
    }
}
