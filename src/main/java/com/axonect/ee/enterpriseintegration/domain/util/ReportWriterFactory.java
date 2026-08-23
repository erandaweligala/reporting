package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.domain.service.ReportWriter;
import com.axonect.ee.enterpriseintegration.domain.service.impl.CsvReportWriter;
import com.axonect.ee.enterpriseintegration.domain.service.impl.ExcelReportWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportWriterFactory {
    private final CsvReportWriter csvWriter;
    private final ExcelReportWriter excelWriter;

    public ReportWriter get(ReportFormat format) {

        return switch (format) {
            case CSV -> csvWriter;
            case EXCEL -> excelWriter;
        };
        }

}
