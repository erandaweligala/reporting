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
        assertEquals(BUCKET_INSTANCE_HEADER, headerOf(TableExtracts.BUCKET_INSTANCE));

        List<String> labels = labelsOf(TableExtracts.BUCKET_INSTANCE);
        assertFalse(labels.contains("IS_UNLIMITED"),
                "the table carries IS_UNLIMITED, the extract deliberately does not");
        assertTrue(labels.indexOf("USAGE") < labels.indexOf("UPDATED_AT"),
                "the sample reports USAGE before UPDATED_AT, the DDL orders them the other way");
    }

    @Test
    void serviceIdIsTheKeyTheBucketExtractPointsAt() {
        assertEquals("si.ID", sourceOf(TableExtracts.MAC_SERVICE_TABLE, "SERVICE_ID"));
        assertEquals("b.SERVICE_ID", sourceOf(TableExtracts.BUCKET_INSTANCE, "SERVICE_ID"));
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
        assertEquals(List.of("EXPIRATION", "UPDATED_AT"), timestampLabelsOf(TableExtracts.BUCKET_INSTANCE));
    }

    @Test
    void everyExtractIsRegisterableUnderADistinctReportType() {
        assertEquals(List.of(TableExtracts.MAC_SERVICE_TABLE, TableExtracts.PLAN_TO_BUCKET,
                TableExtracts.BUCKET_INSTANCE), TableExtracts.ALL);
        assertEquals(TableExtracts.ALL.size(),
                TableExtracts.ALL.stream().map(Spec::reportType).distinct().count());
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

    private static String sourceOf(Spec spec, String label) {
        return spec.columns().stream()
                .filter(column -> column.label().equals(label))
                .map(Column::source)
                .collect(Collectors.joining());
    }
}
