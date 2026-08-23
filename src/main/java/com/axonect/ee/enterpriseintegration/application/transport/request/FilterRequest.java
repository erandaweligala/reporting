package com.axonect.ee.enterpriseintegration.application.transport.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FilterRequest {
    @Builder.Default
    private Integer page = 1;

    @Builder.Default
    private Integer pageSize = 10;

    private String createdBy;
    private String reportType;
    private String reportStatus;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
}

