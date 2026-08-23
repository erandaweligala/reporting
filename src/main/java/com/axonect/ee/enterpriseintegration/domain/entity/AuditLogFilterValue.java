package com.axonect.ee.enterpriseintegration.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogFilterValue {
    private String columnName;
    private String operation;
    private String[] value;
}
