package com.axonect.ee.enterpriseintegration.application.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

public class BaseController {

    /**
     * Standard API response wrapper
     */
    @Getter
    @Setter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiResponse {
        private boolean success;
        private String message;
        private Object data;

        public ApiResponse(boolean success, Object data) {
            this.success = success;
            this.data = data;
            this.message = null;
        }
    }
}
