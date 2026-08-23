package com.axonect.ee.enterpriseintegration.domain.exception;

public class ReportClientException extends RuntimeException{
    public ReportClientException(String message, Throwable cause) {
        super(message,cause);
    }
}
