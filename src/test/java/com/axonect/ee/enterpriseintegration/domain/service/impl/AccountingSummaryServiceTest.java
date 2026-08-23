package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.controller.BaseController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountingSummaryServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private AccountingSummaryService accountingSummaryService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

//    @Test
//    void getAccountingSummary_WhenRedisHasValidIntegerValues_ShouldReturnCounts() {
//        // Given
//        when(valueOperations.get("AUTHENTICATION::Success")).thenReturn(10);
//        when(valueOperations.get("AUTHENTICATION::Failure")).thenReturn(2);
//        when(valueOperations.get("COA::Request")).thenReturn(7);
//        when(valueOperations.get("COA::Failure")).thenReturn(1);
//
//        // When
//        BaseController.ApiResponse response = accountingSummaryService.getAccountingSummary();
//
//        // Then
//        assertTrue(response.isSuccess());
//        assertEquals("Accounting summary fetched successfully", response.getMessage());
//
//        Map<String, Integer> data = (Map<String, Integer>) response.getData();
//        assertEquals(10, data.get("authenticationSuccessCount"));
//        assertEquals(2, data.get("authenticationFailureCount"));
//        assertEquals(7, data.get("coaRequestCount"));
//        assertEquals(1, data.get("coaFailureCount"));
//    }

//    @Test
//    void getAccountingSummary_WhenRedisValuesAreStrings_ShouldParseCorrectly() {
//        // Given
//        when(valueOperations.get("AUTHENTICATION::Success")).thenReturn("5");
//        when(valueOperations.get("AUTHENTICATION::Failure")).thenReturn("3");
//        when(valueOperations.get("COA::Request")).thenReturn("8");
//        when(valueOperations.get("COA::Failure")).thenReturn("0");
//
//        // When
//        BaseController.ApiResponse response = accountingSummaryService.getAccountingSummary();
//
//        // Then
//        Map<String, Integer> data = (Map<String, Integer>) response.getData();
//        assertEquals(0, data.get("authenticationSuccessCount"));
//        assertEquals(0, data.get("authenticationFailureCount"));
//        assertEquals(8, data.get("coaRequestCount"));
//        assertEquals(0, data.get("coaFailureCount"));
//    }

    @Test
    void getAccountingSummary_WhenRedisReturnsNull_ShouldReturnZeros() {
        // Given
        when(valueOperations.get(anyString())).thenReturn(null);

        // When
        BaseController.ApiResponse response = accountingSummaryService.getAccountingSummary();

        // Then
        Map<String, Integer> data = (Map<String, Integer>) response.getData();
        assertEquals(0, data.get("authenticationSuccessCount"));
        assertEquals(0, data.get("authenticationFailureCount"));
        assertEquals(0, data.get("coaRequestCount"));
        assertEquals(0, data.get("coaFailureCount"));
    }

    @Test
    void getAccountingSummary_WhenRedisReturnsUnsupportedType_ShouldReturnZero() {
        // Given
        when(valueOperations.get("AUTHENTICATION::Success")).thenReturn(new Object());

        // When
        BaseController.ApiResponse response = accountingSummaryService.getAccountingSummary();

        // Then
        Map<String, Integer> data = (Map<String, Integer>) response.getData();
        assertEquals(0, data.get("authenticationSuccessCount"));
    }

    @Test
    void getAccountingSummary_WhenRedisThrowsException_ShouldReturnZeros() {
        // Given
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));

        // When
        BaseController.ApiResponse response = accountingSummaryService.getAccountingSummary();

        // Then
        Map<String, Integer> data = (Map<String, Integer>) response.getData();
        assertEquals(0, data.get("authenticationSuccessCount"));
        assertEquals(0, data.get("authenticationFailureCount"));
        assertEquals(0, data.get("coaRequestCount"));
        assertEquals(0, data.get("coaFailureCount"));
    }
}

