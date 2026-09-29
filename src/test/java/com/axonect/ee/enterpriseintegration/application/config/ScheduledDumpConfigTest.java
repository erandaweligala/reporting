package com.axonect.ee.enterpriseintegration.application.config;

import com.axonect.ee.enterpriseintegration.domain.service.ReportDefinition;
import com.axonect.ee.enterpriseintegration.domain.service.impl.ScheduledDumpJob;
import com.axonect.ee.enterpriseintegration.domain.util.ReportDefinitionsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What the scheduler registers is decided once, at startup, so the decision is what is held to
 * here: nothing when it is off, one task at the configured time when it is on, and a failed start
 * rather than a silent night when the configuration cannot be what was meant.
 */
class ScheduledDumpConfigTest {

    private static final List<String> DUMPS =
            List.of("USER_DATA_DUMP", "MAC_SERVICE_TABLE", "PLAN_TO_BUCKET", "BUCKET_INSTANCE");

    private final ScheduledDumpProperties properties = new ScheduledDumpProperties();
    private final UserDumpProperties userDumpProperties = new UserDumpProperties();
    private final ScheduledDumpJob job = mock(ScheduledDumpJob.class);
    private final ScheduledDumpConfig config = new ScheduledDumpConfig(properties, userDumpProperties, job);
    private final ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

    private MockedStatic<ReportDefinitionsRegistry> registry;

    @BeforeEach
    void setUp() {
        registry = mockStatic(ReportDefinitionsRegistry.class);
        registry.when(() -> ReportDefinitionsRegistry.getDefinition(anyString()))
                .thenReturn(mock(ReportDefinition.class));
        properties.setEnabled(true);
        userDumpProperties.setTimezone("UTC");
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void offByDefaultInCode() {
        assertEquals(false, new ScheduledDumpProperties().isEnabled());
    }

    @Test
    void registersNothingWhenDisabled() {
        properties.setEnabled(false);

        config.configureTasks(registrar);

        assertTrue(registrar.getCronTaskList().isEmpty());
        verifyNoInteractions(job);
    }

    @Test
    void byDefaultRequestsTheFourDumpsEveryDayAtHalfPastMidnight() {
        config.configureTasks(registrar);

        CronTask task = onlyTask();
        assertEquals("0 30 0 * * *", task.getExpression());

        task.getRunnable().run();
        verify(job).run(DUMPS);
    }

    @Test
    void readsTheCronInTheUserDumpsZoneWhenNoneIsGiven() {
        userDumpProperties.setTimezone("Asia/Colombo");

        config.configureTasks(registrar);

        // 23:30 in Colombo (UTC+05:30); the next 00:30 there is an hour later.
        assertEquals(Instant.parse("2026-09-28T19:00:00Z"), nextRunAfter(Instant.parse("2026-09-28T18:00:00Z")));
    }

    @Test
    void anExplicitZoneOverridesTheUserDumps() {
        userDumpProperties.setTimezone("Asia/Colombo");
        properties.setZone("UTC");

        config.configureTasks(registrar);

        assertEquals(Instant.parse("2026-09-29T00:30:00Z"), nextRunAfter(Instant.parse("2026-09-28T18:00:00Z")));
    }

    @Test
    void takesTheConfiguredTime() {
        properties.setCron("0 15 2 * * *");

        config.configureTasks(registrar);

        assertEquals(Instant.parse("2026-09-29T02:15:00Z"), nextRunAfter(Instant.parse("2026-09-28T18:00:00Z")));
    }

    @Test
    void normalisesTheReportTypesItIsGiven() {
        properties.setReportTypes(List.of(" user_data_dump ", "PLAN_TO_BUCKET", "", "plan_to_bucket"));

        config.configureTasks(registrar);

        onlyTask().getRunnable().run();
        verify(job).run(List.of("USER_DATA_DUMP", "PLAN_TO_BUCKET"));
    }

    @Test
    void registersNothingWhenNoReportTypeIsGiven() {
        properties.setReportTypes(List.of());

        config.configureTasks(registrar);

        assertTrue(registrar.getCronTaskList().isEmpty());
    }

    @Test
    void failsTheStartOnAnUnknownReportType() {
        registry.when(() -> ReportDefinitionsRegistry.getDefinition("USER_DUMP"))
                .thenThrow(new IllegalArgumentException("No definition registered for report type: USER_DUMP"));
        properties.setReportTypes(List.of("USER_DUMP"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> config.configureTasks(registrar));

        assertTrue(e.getMessage().contains("report.scheduled-dump.report-types"), e.getMessage());
        assertTrue(e.getMessage().contains("USER_DUMP"), e.getMessage());
    }

    @Test
    void failsTheStartOnACronThatIsNotOne() {
        // Five fields: the Unix form, which Spring reads as missing its seconds.
        properties.setCron("30 0 * * *");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> config.configureTasks(registrar));

        assertTrue(e.getMessage().contains("report.scheduled-dump.cron"), e.getMessage());
    }

    @Test
    void failsTheStartOnAZoneThatIsNotOne() {
        properties.setZone("Asia/Nowhere");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> config.configureTasks(registrar));

        assertTrue(e.getMessage().contains("report.scheduled-dump.zone"), e.getMessage());
    }

    private CronTask onlyTask() {
        List<CronTask> tasks = registrar.getCronTaskList();
        assertEquals(1, tasks.size());
        return tasks.get(0);
    }

    private Instant nextRunAfter(Instant now) {
        CronTrigger trigger = (CronTrigger) onlyTask().getTrigger();
        return trigger.nextExecution(new SimpleTriggerContext(Clock.fixed(now, ZoneOffset.UTC)));
    }
}
