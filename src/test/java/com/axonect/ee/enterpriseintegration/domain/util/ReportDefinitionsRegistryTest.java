package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReportDefinitionsRegistryTest {

    @BeforeEach
    void clearRegistry() throws Exception {
        // Clear static registry via reflection (since no clear() method exists)
        var field = ReportDefinitionsRegistry.class.getDeclaredField("registry");
        field.setAccessible(true);
        ((java.util.Map<?, ?>) field.get(null)).clear();
    }

    @Test
    void register_and_getDefinition_shouldReturnRegisteredDefinition() {
        ReportDefinition definition = mock(ReportDefinition.class);

        ReportDefinitionsRegistry.register("TEST_REPORT", definition);

        ReportDefinition result =
                ReportDefinitionsRegistry.getDefinition("TEST_REPORT");

        assertSame(definition, result);
    }

    @Test
    void getDefinition_whenNotRegistered_shouldThrowException() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ReportDefinitionsRegistry.getDefinition("UNKNOWN_REPORT")
        );

        assertEquals(
                "No definition registered for report type: UNKNOWN_REPORT",
                ex.getMessage()
        );
    }
}

