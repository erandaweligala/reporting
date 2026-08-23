package com.axonect.ee.enterpriseintegration.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogRequest {
    private List<AuditLogFilterValue> filterValues;
    private Integer offset;
    private Integer limit;
}
