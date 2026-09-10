package com.axonect.ee.enterpriseintegration.domain.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExcelSafeTimestampTest {

    @Test
    void wrapsATimestampInTheFormulaASpreadsheetDisplaysVerbatim() {
        // ="..." is a formula whose value is the string, so Excel shows the characters rather than
        // converting them to the day number behind them — which is what put 46271.07939 in every
        // date column of the dump.
        assertEquals("=\"2026-09-06 01:54:19\"", ExcelSafeTimestamp.render("2026-09-06 01:54:19"));
    }

    @Test
    void dropsAFractionalSecondsFieldSoTheDisplayedFormatIsNotTheConfiguredOne() {
        // A deployment whose date-format still carries FF3 must produce the same cell as one whose
        // model does not. Normalising here is what makes yyyy-MM-dd HH:mm:ss a property of the
        // file rather than of the configuration.
        assertEquals("=\"2026-09-06 01:54:19\"", ExcelSafeTimestamp.render("2026-09-06 01:54:19.123"));
        assertEquals("=\"2026-09-06 01:54:19\"", ExcelSafeTimestamp.render("2026-09-06 01:54:19.123456"));
        assertEquals("=\"2026-09-06 01:54:19\"", ExcelSafeTimestamp.render("2026-09-06 01:54:19,123"));
    }

    @Test
    void normalisesAnIsoSeparatorToASpace() {
        assertEquals("=\"2026-09-06 01:54:19\"", ExcelSafeTimestamp.render("2026-09-06T01:54:19"));
    }

    @Test
    void rendersADateThatCarriesNoTime() {
        assertEquals("=\"2026-09-06\"", ExcelSafeTimestamp.render("2026-09-06"));
    }

    @Test
    void leavesEverythingThatIsNotATimestampForTheCallerToWriteUnchanged() {
        // Null is what an empty column arrives as; the rest are values that share a column with a
        // timestamp only when something upstream has gone wrong, and must not be dressed up as one.
        assertNull(ExcelSafeTimestamp.render(null));
        assertNull(ExcelSafeTimestamp.render(""));
        assertNull(ExcelSafeTimestamp.render("taiwowilliams"));
        assertNull(ExcelSafeTimestamp.render("46271.07939"));
        assertNull(ExcelSafeTimestamp.render("192.168.10.30"));
        assertNull(ExcelSafeTimestamp.render("14/08/2026 17:02:00"));
        assertNull(ExcelSafeTimestamp.render("2026-09-06 01:54"));
        assertNull(ExcelSafeTimestamp.render("prefix 2026-09-06 01:54:19"));
        assertNull(ExcelSafeTimestamp.render("2026-09-06 01:54:19 suffix"));
    }
}
