package com.axonect.ee.enterpriseintegration.domain.constant;

import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Column;
import com.axonect.ee.enterpriseintegration.domain.constant.TableExtractSql.Spec;
import org.junit.jupiter.api.Test;

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
        // several, so neither belongs in an extract.
        String sql = TableExtractSql.build(TableExtracts.BUCKET_INSTANCE, DATE_FORMAT).sql();

        assertFalse(sql.contains("ORDER BY"), "an extract must not pay for an ordering nobody reads");
        assertFalse(sql.contains("GROUP BY"));
        assertFalse(sql.contains("WHERE"));
        assertEquals(1, sql.split(" FROM ", -1).length - 1, "one table, read once");
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
        for (Spec spec : TableExtracts.ALL) {
            String selectList = TableExtractSql.build(spec, DATE_FORMAT).sql();
            selectList = selectList.substring("SELECT ".length(), selectList.indexOf(" FROM "));

            // Commas inside DECODE(...) are part of one expression, so count at the top level.
            assertEquals(spec.columns().size(), countTopLevel(selectList) + 1,
                    spec.reportType() + " must select one expression per column");
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
