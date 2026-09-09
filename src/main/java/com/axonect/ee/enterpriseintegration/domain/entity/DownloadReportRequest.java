package com.axonect.ee.enterpriseintegration.domain.entity;

import com.axonect.ee.enterpriseintegration.application.transport.request.FilterValue;
import com.axonect.ee.enterpriseintegration.application.transport.response.ColumnMetaData;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import javax.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadReportRequest {

    @NotNull(message ="Created By is mandatory")
    private String createdBy;
    @NotNull(message ="Report Type is mandatory")
    private String reportType ;
    /**
     * CSV or EXCEL. Optional: when it is left out the report type decides, so a caller that
     * does not care keeps getting a spreadsheet and a CSV-only report such as USER_DATA_DUMP
     * still runs instead of failing on a format it cannot produce.
     */
    private String format;
    private String classificationLevel;
    private List<FilterValue> filterValues;

}
