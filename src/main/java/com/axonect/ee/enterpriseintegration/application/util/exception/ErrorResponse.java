package com.axonect.ee.enterpriseintegration.application.util.exception;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ErrorResponse {
    private String timestamp;
    private String responseCode;
    private String responseDescription;
    private String path;
}

