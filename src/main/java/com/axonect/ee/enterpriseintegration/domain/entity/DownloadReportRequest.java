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
    private String classificationLevel;
    /**
     * CSV or EXCEL. Optional — left out, a paged report is produced as EXCEL as before and a
     * streaming report (USER_DATA_DUMP) as CSV, which is the only format it has.
     */
    private String format;
    private List<FilterValue> filterValues;

}
