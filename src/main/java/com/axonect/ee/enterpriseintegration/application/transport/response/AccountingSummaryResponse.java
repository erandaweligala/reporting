package com.axonect.ee.enterpriseintegration.application.transport.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingSummaryResponse {

    private Data data;

    @lombok.Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Data {
        private Integer authenticationSuccessCount;
        private Integer authenticationFailureCount;
        private Integer coaRequestCount;
        private Integer coaFailureCount;
    }
}
