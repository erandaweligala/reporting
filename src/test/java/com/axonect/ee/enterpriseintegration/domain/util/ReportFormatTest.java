package com.axonect.ee.enterpriseintegration.domain.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReportFormatTest {

    @Test
    void valueOf_shouldReturnCsv() {
        ReportFormat format = ReportFormat.valueOf("CSV");
        assertEquals(ReportFormat.CSV, format);
    }

    @Test
    void valueOf_shouldReturnExcel() {
        ReportFormat format = ReportFormat.valueOf("EXCEL");
        assertEquals(ReportFormat.EXCEL, format);
    }

    @Test
    void values_shouldContainAllEnumConstants() {
        ReportFormat[] values = ReportFormat.values();

        assertEquals(2, values.length);
        assertTrue(java.util.List.of(values).contains(ReportFormat.CSV));
        assertTrue(java.util.List.of(values).contains(ReportFormat.EXCEL));
    }
}

