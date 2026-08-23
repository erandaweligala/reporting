package com.axonect.ee.enterpriseintegration.application.transport.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StatusValue {

    private String text;
    private String value;
}
