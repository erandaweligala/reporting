package com.axonect.ee.enterpriseintegration.domain.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleTextTest {

    private static final String FORMAT = "YYYY-MM-DD HH24:MI:SS.FF3";

    @Test
    void castsTheColumnSoAFractionalSecondsModelIsLegalOnADateColumn() {
        assertEquals("TO_CHAR(CAST(u.CREATED_DATE AS TIMESTAMP), '" + FORMAT + "')",
                OracleText.timestampAsText("u.CREATED_DATE", FORMAT));
    }

    @Test
    void namesTheRenderedColumnOutsideTheCastRatherThanInsideIt() {
        // The alias belongs to the finished expression. Inside the CAST it is a second AS where
        // the closing bracket belongs, which is ORA-00907 for the whole statement.
        assertEquals("TO_CHAR(CAST(u.CREATED_DATE AS TIMESTAMP), '" + FORMAT + "') "
                        + "AS CUSTOMER_ACTIVATION_DATE",
                OracleText.timestampAsText("u.CREATED_DATE", FORMAT, "CUSTOMER_ACTIVATION_DATE"));
    }

    @Test
    void rejectsAColumnThatCarriesItsOwnAlias() {
        // The exact fragment that failed every shard of every dump run:
        //   CAST(u.CREATED_DATE as CUSTOMER_ACTIVATION_DATE AS TIMESTAMP)
        // The database is the wrong place to find this out — the report has run for minutes by
        // then, the whole statement is rejected rather than the column, and the ORA number names
        // the complaint but not the fragment that provoked it.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OracleText.timestampAsText("u.CREATED_DATE as CUSTOMER_ACTIVATION_DATE", FORMAT));

        assertTrue(thrown.getMessage().contains("u.CREATED_DATE as CUSTOMER_ACTIVATION_DATE"),
                "the message has to name the fragment, which is what the ORA error cannot do");
    }

    @Test
    void rejectsAColumnThatIsNotAPlainReference() {
        // Everything else that cannot survive being interpolated into a CAST operand.
        assertThrows(IllegalArgumentException.class,
                () -> OracleText.timestampAsText("NVL(u.CREATED_DATE, u.UPDATED_DATE)", FORMAT));
        assertThrows(IllegalArgumentException.class,
                () -> OracleText.timestampAsText("u.CREATED_DATE, u.UPDATED_DATE", FORMAT));
        assertThrows(IllegalArgumentException.class,
                () -> OracleText.timestampAsText("u.CREATED_DATE) AS TIMESTAMP), (SELECT 1 FROM DUAL", FORMAT));
        assertThrows(IllegalArgumentException.class, () -> OracleText.timestampAsText("", FORMAT));
        assertThrows(IllegalArgumentException.class, () -> OracleText.timestampAsText(null, FORMAT));
    }

    @Test
    void acceptsAColumnWithOrWithoutItsTableAlias() {
        assertTrue(OracleText.timestampAsText("CREATED_DATE", FORMAT).contains("CAST(CREATED_DATE AS"));
        assertTrue(OracleText.timestampAsText("u.CREATED_DATE", FORMAT).contains("CAST(u.CREATED_DATE AS"));
        assertEquals("SI.CYCLE_START_DATE$#", OracleText.validateColumn("SI.CYCLE_START_DATE$#"));
    }

    @Test
    void rejectsAnAliasThatIsNotAnIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> OracleText.timestampAsText("u.CREATED_DATE", FORMAT, "CUSTOMER ACTIVATION DATE"));
        assertThrows(IllegalArgumentException.class,
                () -> OracleText.timestampAsText("u.CREATED_DATE", FORMAT, "u.CREATED_DATE"));
        assertThrows(IllegalArgumentException.class,
                () -> OracleText.timestampAsText("u.CREATED_DATE", FORMAT, ""));
    }

    @Test
    void rejectsAFormatModelThatCouldCarrySql() {
        assertThrows(IllegalArgumentException.class,
                () -> OracleText.timestampAsText("u.CREATED_DATE", "YYYY') || (SELECT PASSWORD FROM AAA_USER) || ('"));
        assertThrows(IllegalArgumentException.class,
                () -> OracleText.timestampAsText("u.CREATED_DATE", "  "));
        assertThrows(IllegalArgumentException.class,
                () -> OracleText.timestampAsText("u.CREATED_DATE", null));
    }
}
