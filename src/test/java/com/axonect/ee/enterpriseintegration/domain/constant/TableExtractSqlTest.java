package com.axonect.ee.enterpriseintegration.domain.constant;

import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Column;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableExtractSqlTest {

    private static final String DATE_FORMAT = "YYYY-MM-DD HH24:MI:SS.FF3";

    private static final Spec SPEC = new Spec("SAMPLE", "SAMPLE_TABLE t", List.of(
            Column.plain("ID", "t.ID"),
            Column.at("CREATED_AT", "t.CREATED_AT")));

    /** An extract with a column the database does not hold, and the column it is looked up by. */
    private static final Spec SPLICED = new Spec("SPLICED", "SAMPLE_TABLE t JOIN OTHER o ON o.ID = t.O_ID",
            List.of(Column.plain("ID", "t.ID"),
                    Column.spliced("USAGE"),
                    Column.at("UPDATED_AT", "t.UPDATED_AT")),
            List.of(Column.plain("USER_NAME", "o.USERNAME")),
            "o.USERNAME NULLS LAST");

    @Test
    void selectsTheSpecsColumnsInOrderOverItsFromClause() {
        SqlStatement statement = TableExtractSql.build(SPEC, DATE_FORMAT);

        assertEquals("SELECT t.ID, TO_CHAR(CAST(t.CREATED_AT AS TIMESTAMP), 'YYYY-MM-DD HH24:MI:SS.FF3') "
                + "FROM SAMPLE_TABLE t", statement.sql());
    }

    @Test
    void carriesNoParametersBecauseAnExtractHasNothingToBind() {
        assertEquals(List.of(), TableExtractSql.build(SPEC, DATE_FORMAT).params());
        assertFalse(TableExtractSql.build(SPEC, DATE_FORMAT).sql().contains("?"));
    }

    @Test
    void timestampsAreCastBeforeTheyAreFormatted() {
        // TO_CHAR of a DATE with an FF element raises ORA-01821, and the extract must not depend
        // on which of DATE and TIMESTAMP a given column happens to be.
        assertTrue(TableExtractSql.build(SPEC, DATE_FORMAT).sql()
                .contains("TO_CHAR(CAST(t.CREATED_AT AS TIMESTAMP)"));
    }

    @Test
    void plainColumnsAreLeftAloneSoTheDatabaseDecidesTheirText() {
        String sql = TableExtractSql.build(SPEC, DATE_FORMAT).sql();

        assertTrue(sql.contains("SELECT t.ID,"));
        assertFalse(sql.contains("TO_CHAR(CAST(t.ID"));
    }

    @Test
    void readsTheTableInOnePassWithNothingAskedOfTheDatabaseBeyondTheProjection() {
        // The plan this statement is meant to get is a single sequential scan. An ORDER BY would
        // trade it for a sort or an index walk, and a shard predicate would trade one scan for
        // several, so neither belongs in an extract that reads only the database. The one
        // exception is the variant that reads the CDR usage in step with the table, which is the
        // test above and is a spec of its own for exactly that reason.
        String sql = TableExtractSql.build(TableExtracts.BUCKET_INSTANCE, DATE_FORMAT).sql();

        assertFalse(sql.contains("ORDER BY"), "an extract must not pay for an ordering nobody reads");
        assertFalse(sql.contains("GROUP BY"));
        assertFalse(sql.contains("WHERE"));
        assertEquals(1, sql.split(" FROM ", -1).length - 1, "one table, read once");
    }

    @Test
    void aSplicedColumnIsLeftOutOfTheSelectListAndTheHelpersFollowTheRest() {
        // The statement must say what it reads: a column filled from outside the database is left
        // out of the select list rather than selected and overwritten.
        assertEquals("SELECT t.ID, TO_CHAR(CAST(t.UPDATED_AT AS TIMESTAMP), 'YYYY-MM-DD HH24:MI:SS.FF3'), "
                        + "o.USERNAME FROM SAMPLE_TABLE t JOIN OTHER o ON o.ID = t.O_ID "
                        + "ORDER BY o.USERNAME NULLS LAST",
                TableExtractSql.build(SPLICED, DATE_FORMAT).sql());
    }

    @Test
    void theBucketExtractReadsNoUsageColumnOnceTheCdrDocumentsAreWhatFillIt() {
        String sql = TableExtractSql.build(TableExtracts.BUCKET_INSTANCE_FROM_CDR, DATE_FORMAT).sql();

        assertFalse(sql.contains("b.USAGE"),
                "USAGE comes from the CDR session documents, so the table's counter is not read");
        assertTrue(sql.contains("si.USERNAME FROM BUCKET_INSTANCE b "
                        + "LEFT JOIN SERVICE_INSTANCE si ON si.ID = b.SERVICE_ID"),
                "the username the figures are keyed on is selected after the extract's columns");
        assertTrue(sql.endsWith(" ORDER BY si.USERNAME NULLS LAST"),
                "an ordering is what lets the aggregation be merge-joined rather than buffered");
        assertTrue(TableExtractSql.build(TableExtracts.BUCKET_INSTANCE, DATE_FORMAT).sql()
                        .contains("b.USAGE"),
                "the fallback variant still reads it");
    }

    @Test
    void everyRegisteredExtractBuildsASelectOverItsOwnTable() {
        assertTrue(TableExtractSql.build(TableExtracts.MAC_SERVICE_TABLE, DATE_FORMAT).sql()
                .contains("FROM SERVICE_INSTANCE si LEFT JOIN AAA_USER u ON u.USER_NAME = si.USERNAME"));
        assertTrue(TableExtractSql.build(TableExtracts.PLAN_TO_BUCKET, DATE_FORMAT).sql()
                .endsWith("FROM PLAN_TO_BUCKET p"));
        assertTrue(TableExtractSql.build(TableExtracts.BUCKET_INSTANCE, DATE_FORMAT).sql()
                .endsWith("FROM BUCKET_INSTANCE b"));
    }

    @Test
    void selectsExactlyOneExpressionPerColumnOfTheExtract() {
        List<Spec> specs = new ArrayList<>(TableExtracts.ALL);
        specs.add(TableExtracts.BUCKET_INSTANCE_FROM_CDR);

        for (Spec spec : specs) {
            String selectList = TableExtractSql.build(spec, DATE_FORMAT).sql();
            selectList = selectList.substring("SELECT ".length(), selectList.indexOf(" FROM "));

            long selected = spec.columns().stream().filter(Column::selected).count();
            // Commas inside DECODE(...) are part of one expression, so count at the top level.
            assertEquals(selected + spec.helpers().size(), countTopLevel(selectList) + 1,
                    spec.reportType() + " must select one expression per selected column, "
                            + "plus its helpers and nothing else");
        }
    }

    @Test
    void aFormatModelThatIsNotOneIsRefusedBeforeItReachesTheStatement() {
        assertThrows(IllegalArgumentException.class,
                () -> TableExtractSql.build(SPEC, "YYYY') FROM DUAL--"));
        assertThrows(IllegalArgumentException.class, () -> TableExtractSql.build(SPEC, " "));
        assertThrows(IllegalArgumentException.class, () -> TableExtractSql.build(SPEC, null));
    }

    private static int countTopLevel(String selectList) {
        int depth = 0;
        int commas = 0;
        for (char c : selectList.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            }
        }
        return commas;
    }
}
