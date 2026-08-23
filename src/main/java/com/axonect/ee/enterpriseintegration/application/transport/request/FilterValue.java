package com.axonect.ee.enterpriseintegration.application.transport.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FilterValue {

    private String columnName;
    private Object value;
    private String operation;

}
