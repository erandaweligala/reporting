package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import com.axonect.ee.enterpriseintegration.domain.service.StreamingReportDefinition;
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
    void isStreaming_whenDefinitionStreams_shouldBeTrue() {
        ReportDefinitionsRegistry.register("STREAMING_REPORT", mock(StreamingReportDefinition.class));

        assertTrue(ReportDefinitionsRegistry.isStreaming("STREAMING_REPORT"));
    }

    @Test
    void isStreaming_whenDefinitionIsPaged_shouldBeFalse() {
        ReportDefinitionsRegistry.register("PAGED_REPORT", mock(ReportDefinition.class));

        assertFalse(ReportDefinitionsRegistry.isStreaming("PAGED_REPORT"));
    }

    @Test
    void isStreaming_whenNotRegistered_shouldBeFalse() {
        assertFalse(ReportDefinitionsRegistry.isStreaming("UNKNOWN_REPORT"));
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

