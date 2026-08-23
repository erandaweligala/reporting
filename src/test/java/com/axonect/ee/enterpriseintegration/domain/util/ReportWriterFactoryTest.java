package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.domain.service.ReportWriter;
import com.axonect.ee.enterpriseintegration.domain.service.impl.CsvReportWriter;
import com.axonect.ee.enterpriseintegration.domain.service.impl.ExcelReportWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

class ReportWriterFactoryTest {

    @Mock
    private CsvReportWriter csvReportWriter;

    @Mock
    private ExcelReportWriter excelReportWriter;

    private ReportWriterFactory reportWriterFactory;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reportWriterFactory = new ReportWriterFactory(csvReportWriter, excelReportWriter);
    }

    @Test
    void get_WhenFormatIsCSV_ShouldReturnCsvWriter() {
        ReportWriter writer = reportWriterFactory.get(ReportFormat.CSV);

        assertNotNull(writer);
        assertEquals(csvReportWriter, writer);
    }

    @Test
    void get_WhenFormatIsEXCEL_ShouldReturnExcelWriter() {
        ReportWriter writer = reportWriterFactory.get(ReportFormat.EXCEL);

        assertNotNull(writer);
        assertEquals(excelReportWriter, writer);
    }
}

