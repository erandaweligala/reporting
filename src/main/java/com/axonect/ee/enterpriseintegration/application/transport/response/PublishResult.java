package com.axonect.ee.enterpriseintegration.application.transport.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishResult {
    private boolean dcSuccess;
    private boolean drSuccess;
    private String dcError;
    private String drError;
    private long dcLatencyMs;
    private long drLatencyMs;

    public boolean isOverallSuccess() {
        return dcSuccess || drSuccess; // At least one succeeded
    }

    public boolean isBothSuccess() {
        return dcSuccess && drSuccess;
    }

    public boolean isCompleteFailure() {
        return !dcSuccess && !drSuccess;
    }
}