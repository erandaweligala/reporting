package com.axonect.ee.enterpriseintegration.application.transport.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class CommonAdaptorResp<T> {

    private boolean success;
    private String message;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private T data;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private PageDetail pageDetail;

    public CommonAdaptorResp(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public CommonAdaptorResp(boolean success, String message, T responseData) {
        this.success = success;
        this.message = message;
        this.data = responseData;
    }
}


