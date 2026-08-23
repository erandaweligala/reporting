package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.constant.LoggingAdviceConstants;
import com.axonect.ee.enterpriseintegration.application.controller.BaseController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingSummaryService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String AUTH_SUCCESS = "authenticationSuccessCount";
    private static final String AUTH_FAILURE = "authenticationFailureCount";
    private static final String COA_REQUEST = "coaRequestCount";
    private static final String COA_FAILURE = "coaFailureCount";

    public BaseController.ApiResponse getAccountingSummary() {

        log.info(LoggingAdviceConstants.REPORT_GENERATION_STARTED);

        Map<String, Integer> responseData = new HashMap<>();

        responseData.put(AUTH_SUCCESS, readRedisValue(AUTH_SUCCESS));
        responseData.put(AUTH_FAILURE, readRedisValue(AUTH_FAILURE));
        responseData.put(COA_REQUEST, readRedisValue(COA_REQUEST));
        responseData.put(COA_FAILURE, readRedisValue(COA_FAILURE));

        log.info(LoggingAdviceConstants.SERVICE_TERMINATION,
                "SUCCESS", "Accounting summary successfully generated");

        return new BaseController.ApiResponse(true,
                "Accounting summary fetched successfully",
                responseData);
    }

    /**
     * Reads value written by another microservice (Quarkus)
     * Values are stored as plain integers/strings, not JSON
     */
    private Integer readRedisValue(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);

            if (value == null) {
                log.warn("Redis key not found: {}", key);
                return 0;
            }

            return Integer.parseInt(value.toString().trim());

        } catch (NumberFormatException ex) {
            log.warn("Could not parse Redis value for key {}: {}", key, ex.getMessage());
            return 0;
        } catch (Exception ex) {
            log.error(LoggingAdviceConstants.EXCEPTION_STACK_TRACE,
                    "REDIS_READ",
                    ex.getMessage(),
                    ex.getStackTrace());
            return 0;
        }
    }
}