package com.axonect.ee.enterpriseintegration.domain.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AAAException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public AAAException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}

