package com.axonect.ee.enterpriseintegration.domain.constant;

import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Column;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The header of each extract is the contract with the consuming system, and it is taken from the
 * sample extracts rather than from the schema. These assertions are the sample: a column reordered,
 * renamed, added or dropped fails here rather than at the consumer's loader.
 */
class TableExtractsTest {

    private static final String MAC_SERVICE_TABLE_HEADER =
            "SERVICE_ID,USER_NAME,PLAN_ID,PLAN_NAME,PLAN_TYPE,RECURRING_FLAG,SERVICE_CYCLE_START_DATE,"
                    + "SERVICE_CYCLE_END_DATE,NEXT_CYCLE_START_DATE,SERVICE_START_DATE,STATUS,CREATED_AT,"
                    + "EXPIRY_DATE,UPDATED_AT,GROUP_ID";

    private static final String PLAN_TO_BUCKET_HEADER =
            "ID,BUCKET_ID,CARRY_FORWARD,CARRY_FORWARD_VALIDITY,CONSUMPTION_LIMIT,CONSUMPTION_LIMIT_WINDOW,"
                    + "CREATED_AT,INITIAL_QUOTA,MAX_CARRY_FORWARD,PLAN_ID,TOTAL_CARRY_FORWARD,UPDATED_AT";

    /**
     * The bucket type the dump reads PLAN_BANDWIDTH under, which is what RULE reports. It is the
     * deployment's {@code report.user-dump.bandwidth-bucket-type}, so the spec is built with it
     * rather than carrying one of its own.
     */
    private static final String BANDWIDTH_TYPE = "BANDWIDTH";

    private static final Spec BUCKET_INSTANCE = TableExtracts.bucketInstance(BANDWIDTH_TYPE);
    private static final Spec BUCKET_INSTANCE_FROM_CDR =
            TableExtracts.bucketInstanceFromCdr(BANDWIDTH_TYPE);

    private static final String BUCKET_INSTANCE_HEADER =
            "ID,BUCKET_ID,BUCKET_TYPE,CARRY_FORWARD,CARRY_FORWARD_VALIDITY,CONSUMPTION_LIMIT,"
                    + "CONSUMPTION_LIMIT_WINDOW,CURRENT_BALANCE,EXPIRATION,INITIAL_BALANCE,MAX_CARRY_FORWARD,"
                    + "PRIORITY,RULE,SERVICE_ID,TIME_WINDOW,TOTAL_CARRY_FORWARD,USAGE,UPDATED_AT";

    @Test
    void macServiceTableMatchesTheSampleHeader() {
        assertEquals(MAC_SERVICE_TABLE_HEADER, headerOf(TableExtracts.MAC_SERVICE_TABLE));
    }

    @Test
    void planToBucketMatchesTheSampleHeader() {
        assertEquals(PLAN_TO_BUCKET_HEADER, headerOf(TableExtracts.PLAN_TO_BUCKET));
    }

    @Test
    void bucketInstanceMatchesTheSampleHeaderRatherThanTheTable() {
        assertEquals(BUCKET_INSTANCE_HEADER, headerOf(BUCKET_INSTANCE));

        List<String> labels = labelsOf(BUCKET_INSTANCE);
        assertFalse(labels.contains("IS_UNLIMITED"),
                "the table carries IS_UNLIMITED, the extract deliberately does not");
        assertTrue(labels.contains(TableExtracts.RULE_COLUMN),
                "RULE keeps its place in the header whatever is reported under it");
        assertTrue(labels.indexOf("USAGE") < labels.indexOf("UPDATED_AT"),
                "the sample reports USAGE before UPDATED_AT, the DDL orders them the other way");
    }

    @Test
    void theCdrVariantOfTheBucketExtractCarriesExactlyTheSameHeader() {
        // Where USAGE is read from is a deployment's business; the header is the consuming
        // system's, and the two variants are registered under the same report type.
        assertEquals(BUCKET_INSTANCE_HEADER, headerOf(BUCKET_INSTANCE_FROM_CDR));
        assertEquals(TableExtracts.BUCKET_INSTANCE_TYPE,
                BUCKET_INSTANCE_FROM_CDR.reportType());
    }

    @Test
    void theCdrVariantSplicesUsageInInsteadOfSelectingTheTablesCounter() {
        assertFalse(columnOf(BUCKET_INSTANCE_FROM_CDR, "USAGE").selected(),
                "the CDR total is not a column of BUCKET_INSTANCE, so the statement selects "
                        + "nothing in its place");
        assertEquals("b.USAGE", sourceOf(BUCKET_INSTANCE, "USAGE"),
                "the fallback variant still reports the table's own counter");
    }

    @Test
    void theCdrVariantJoinsTheUsernameItsFiguresAreKeyedOnAndIsOrderedByIt() {
        Spec spec = BUCKET_INSTANCE_FROM_CDR;

        assertTrue(spec.from().contains("LEFT JOIN SERVICE_INSTANCE si ON si.ID = b.SERVICE_ID"),
                "a bucket whose service no longer resolves is still a row of the table");
        assertEquals(List.of("USER_NAME"), spec.helpers().stream().map(Column::label).toList());
        assertEquals("si.USERNAME", spec.helpers().get(0).source());
        assertEquals("si.USERNAME NULLS LAST, b.EXPIRATION DESC NULLS LAST, b.ID DESC",
                spec.orderBy(),
                "the aggregation is handed out in username order, so the rows are merge-joined "
                        + "against it in that order — with the unresolved services past the end — "
                        + "and a subscriber's own buckets arrive live cycle first, which is the "
                        + "one a figure that covers all of them is reported against");
        assertFalse(labelsOf(spec).contains("USER_NAME"),
                "the username is read from the row and never written to the file");
    }

    @Test
    void theColumnsTheCdrLookupReadsAreFoundByLabelRatherThanCountedByHand() {
        // A spliced column shifts every result set column after it, so these are derived from the
        // spec. BucketUsageColumnSource asks for exactly these four.
        Spec spec = BUCKET_INSTANCE_FROM_CDR;

        assertEquals(16, spec.csvIndex(TableExtracts.USAGE_COLUMN));
        assertEquals(13, spec.csvIndex(TableExtracts.SERVICE_ID_COLUMN));
        assertEquals(1, spec.csvIndex(TableExtracts.BUCKET_ID_COLUMN));
        assertEquals(18, spec.resultIndex(TableExtracts.USER_NAME_HELPER),
                "17 selected columns, then the helper: one fewer than the 18 the CSV carries");
        assertEquals(17, spec.resultIndex("UPDATED_AT"),
                "the column after USAGE is one place to the left of its CSV position");
    }

    @Test
    void theTableSourcedBucketExtractReadsNothingOutsideTheDatabaseAndIsUnordered() {
        assertFalse(BUCKET_INSTANCE.ordered(),
                "without the CDR lookup there is nothing to read the table in step with");
        assertEquals(List.of(), BUCKET_INSTANCE.helpers());
        assertTrue(BUCKET_INSTANCE.from().startsWith("BUCKET_INSTANCE b LEFT JOIN ("),
                "one table, and the bandwidth bucket RULE reports joined in beside it");
    }

    @Test
    void ruleReportsThePlanBandwidthOfTheRowsOwnBundleRatherThanTheTablesRuleColumn() {
        // The dump reports the bundle's bandwidth bucket as PLAN_BANDWIDTH; the consuming system
        // reads it here under the name the sample gives the column. The two are the same figure,
        // so they are read the same way — b.RULE is a different column of a different meaning and
        // is deliberately not what is extracted.
        for (Spec spec : List.of(BUCKET_INSTANCE, BUCKET_INSTANCE_FROM_CDR)) {
            assertEquals("bw.PLAN_BANDWIDTH", sourceOf(spec, TableExtracts.RULE_COLUMN),
                    spec.reportType() + " reports the bundle's bandwidth bucket as RULE");
            assertTrue(spec.from().contains(
                            "LEFT JOIN (SELECT SERVICE_ID, MAX(BUCKET_ID) AS PLAN_BANDWIDTH "
                                    + "FROM BUCKET_INSTANCE WHERE BUCKET_TYPE = ? "
                                    + "GROUP BY SERVICE_ID) bw ON bw.SERVICE_ID = b.SERVICE_ID"),
                    "the dump's own definition of the figure, pre-aggregated per service");
            assertEquals(List.of(BANDWIDTH_TYPE), spec.params(),
                    "the bucket type is bound, not written into the statement");
        }
    }

    @Test
    void aBucketWithNoBandwidthBucketIsStillARowOfTheExtract() {
        // A LEFT join, like every other join in these extracts: the figure is missing, the row is
        // not. An inner join would drop every bucket of every bundle that holds no bandwidth
        // bucket out of an extract that is supposed to be a row per row of the table.
        for (Spec spec : List.of(BUCKET_INSTANCE, BUCKET_INSTANCE_FROM_CDR)) {
            assertFalse(spec.from().contains("INNER JOIN"), spec.reportType());
            assertEquals(1, spec.from().split("LEFT JOIN \\(", -1).length - 1,
                    spec.reportType() + " joins the bandwidth bucket exactly once");
        }
    }

    @Test
    void aBucketTypeThatIsNotConfiguredLeavesRuleAsEmptyAsTheDumpLeavesPlanBandwidth() {
        // Binding an untyped null is what the driver cannot be handed; an empty string matches no
        // bucket type, which is the empty PLAN_BANDWIDTH the dump reports under the same setting.
        assertEquals(List.of(""), TableExtracts.bucketInstance(null).params());
        assertEquals(List.of(""), TableExtracts.bucketInstanceFromCdr(null).params());
    }

    @Test
    void serviceIdIsTheKeyTheBucketExtractPointsAt() {
        assertEquals("si.ID", sourceOf(TableExtracts.MAC_SERVICE_TABLE, "SERVICE_ID"));
        assertEquals("b.SERVICE_ID", sourceOf(BUCKET_INSTANCE, "SERVICE_ID"));
    }

    @Test
    void recurringFlagIsMappedToYesNoInSqlRatherThanLeftAsANumber() {
        assertEquals("DECODE(si.RECURRING_FLAG, 1, 'Yes', 'No')",
                sourceOf(TableExtracts.MAC_SERVICE_TABLE, "RECURRING_FLAG"));
    }

    @Test
    void groupIdComesFromTheSubscriberAndNotFromTheServicesOwnFlag() {
        assertEquals("u.GROUP_ID", sourceOf(TableExtracts.MAC_SERVICE_TABLE, "GROUP_ID"));
        assertTrue(TableExtracts.MAC_SERVICE_TABLE.from().contains("LEFT JOIN AAA_USER"),
                "a service whose user no longer resolves must still be extracted");
        assertFalse(TableExtracts.MAC_SERVICE_TABLE.columns().stream()
                        .anyMatch(column -> column.source().contains("IS_GROUP")),
                "IS_GROUP is a flag on the service, not the subscriber's group id");
    }

    @Test
    void everyDateColumnOfTheSamplesIsRenderedUnderTheExtractsOwnFormat() {
        assertEquals(List.of("SERVICE_CYCLE_START_DATE", "SERVICE_CYCLE_END_DATE", "NEXT_CYCLE_START_DATE",
                        "SERVICE_START_DATE", "CREATED_AT", "EXPIRY_DATE", "UPDATED_AT"),
                timestampLabelsOf(TableExtracts.MAC_SERVICE_TABLE));
        assertEquals(List.of("CREATED_AT", "UPDATED_AT"), timestampLabelsOf(TableExtracts.PLAN_TO_BUCKET));
        assertEquals(List.of("EXPIRATION", "UPDATED_AT"), timestampLabelsOf(BUCKET_INSTANCE));
    }

    @Test
    void everyExtractIsRegisterableUnderADistinctReportType() {
        assertEquals(List.of(TableExtracts.MAC_SERVICE_TABLE, TableExtracts.PLAN_TO_BUCKET,
                BUCKET_INSTANCE), TableExtracts.all(BANDWIDTH_TYPE));
        assertEquals(TableExtracts.all(BANDWIDTH_TYPE).size(),
                TableExtracts.all(BANDWIDTH_TYPE).stream().map(Spec::reportType).distinct().count());
    }

    private static String headerOf(Spec spec) {
        return String.join(",", labelsOf(spec));
    }

    private static List<String> labelsOf(Spec spec) {
        return spec.columns().stream().map(Column::label).toList();
    }

    private static List<String> timestampLabelsOf(Spec spec) {
        return spec.columns().stream().filter(Column::timestamp).map(Column::label).toList();
    }

    private static Column columnOf(Spec spec, String label) {
        return spec.columns().stream()
                .filter(column -> column.label().equals(label))
                .findFirst()
                .orElseThrow();
    }

    private static String sourceOf(Spec spec, String label) {
        return spec.columns().stream()
                .filter(column -> column.label().equals(label))
                .map(Column::source)
                .collect(Collectors.joining());
    }
}
