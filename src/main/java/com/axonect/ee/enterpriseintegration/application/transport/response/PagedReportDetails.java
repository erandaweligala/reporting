package com.axonect.ee.enterpriseintegration.application.transport.response;

import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PagedReportDetails {

    private Integer page;
    private Integer pageSize;
    private Long totalRecords;
    private List<DownloadReport> reportDetails;
}
