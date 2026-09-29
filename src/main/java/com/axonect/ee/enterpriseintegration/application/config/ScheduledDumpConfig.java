package com.axonect.ee.enterpriseintegration.application.config;

import com.axonect.ee.enterpriseintegration.domain.service.impl.ScheduledDumpJob;
import com.axonect.ee.enterpriseintegration.domain.util.ReportDefinitionsRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

import javax.sql.DataSource;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Registers the scheduled dumps from {@link ScheduledDumpProperties}.
 *
 * <p>The task is registered here rather than through {@code @Scheduled} so that everything about
 * it is decided, and checked, once at startup: a disabled job registers nothing at all, a zone
 * left empty follows the user data dump's own, and a cron expression, zone or report type that
 * cannot be what was meant fails the start with the property named — instead of surfacing at
 * 00:30 as a night without dumps. The startup log says which of those it came to, and when the
 * first run will be.
 */
@Configuration
@Slf4j
public class ScheduledDumpConfig implements SchedulingConfigurer {

    private static final String PREFIX = "report.scheduled-dump";

    private final ScheduledDumpProperties properties;
    private final UserDumpProperties userDumpProperties;
    private final ScheduledDumpJob job;
    private final DataSource dataSource;

    public ScheduledDumpConfig(ScheduledDumpProperties properties,
                               UserDumpProperties userDumpProperties,
                               ScheduledDumpJob job,
                               DataSource dataSource) {
        this.properties = properties;
        this.userDumpProperties = userDumpProperties;
        this.job = job;
        this.dataSource = dataSource;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.isEnabled()) {
            log.info("Scheduled dumps are off: {}.enabled is false", PREFIX);
            return;
        }

        List<String> reportTypes = reportTypes();
        if (reportTypes.isEmpty()) {
            log.warn("Scheduled dumps are on but {}.report-types names no report; nothing is scheduled", PREFIX);
            return;
        }

        String cron = properties.getCron() == null ? "" : properties.getCron().trim();
        CronExpression expression = cronExpression(cron);
        ZoneId zone = zone();
        String lock = lock();

        registrar.addCronTask(new CronTask(() -> job.run(reportTypes), new CronTrigger(cron, zone)));

        log.info("Scheduled dumps are on: {} at '{}' in {}, first run at {}; {}",
                reportTypes, cron, zone, expression.next(ZonedDateTime.now(zone)), lock);
    }

    /** Checks the lock settings and says, for the startup log, how a firing is shared out. */
    private String lock() {
        ScheduledDumpProperties.Lock lock = properties.getLock();
        if (!lock.isEnabled()) {
            return "no lock (" + PREFIX + ".lock.enabled is false): every instance runs every firing";
        }

        Duration atLeastFor = lock.getAtLeastFor();
        Duration atMostFor = lock.getAtMostFor();
        if (atLeastFor == null || atMostFor == null || atLeastFor.isNegative() || !atMostFor.isPositive()
                || atLeastFor.compareTo(atMostFor) > 0) {
            throw new IllegalStateException(PREFIX + ".lock.at-least-for (" + atLeastFor + ") and "
                    + PREFIX + ".lock.at-most-for (" + atMostFor + ") must be durations with at-least-for "
                    + "no longer than at-most-for");
        }
        checkLockTable(lock.getTableName());
        return "one instance per firing, through the lock in " + lock.getTableName()
                + ", held " + atLeastFor + " to " + atMostFor;
    }

    /**
     * Looks for the lock table once, so that a deployment without it hears so at startup rather
     * than at the first firing, when every instance would log that it could not take the lock and
     * none would request the dumps. It warns rather than failing the start: a database that is
     * briefly out of reach now says nothing about the table.
     */
    private void checkLockTable(String tableName) {
        try {
            new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM " + tableName + " WHERE 1 = 0",
                    Integer.class);
        } catch (Exception e) {
            log.warn("Scheduled dumps: the lock table {} could not be read ({}). No instance will request "
                            + "the dumps until it exists — see docs/scheduled-dumps.md for its DDL",
                    tableName, e.getMessage());
        }
    }

    /**
     * The configured report types, trimmed and upper-cased as the registry keys them, each checked
     * against the registry. The registry is filled while the context starts, and tasks are
     * registered only once it has finished starting, so every report type there will ever be is
     * already in it.
     */
    private List<String> reportTypes() {
        List<String> configured = properties.getReportTypes() == null ? List.of() : properties.getReportTypes();
        List<String> reportTypes = configured.stream()
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();

        for (String reportType : reportTypes) {
            try {
                ReportDefinitionsRegistry.getDefinition(reportType);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(PREFIX + ".report-types names " + reportType
                        + ", which is not a report type this service produces", e);
            }
        }
        return reportTypes;
    }

    private CronExpression cronExpression(String cron) {
        try {
            return CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PREFIX + ".cron '" + cron + "' is not a cron expression. "
                    + "It takes six fields, starting from the second: '0 30 0 * * *' is every day at 00:30", e);
        }
    }

    private ZoneId zone() {
        boolean inherited = properties.getZone() == null || properties.getZone().isBlank();
        String zone = inherited ? userDumpProperties.getTimezone() : properties.getZone().trim();
        if (zone == null || zone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            String source = inherited ? "report.user-dump.timezone" : PREFIX + ".zone";
            throw new IllegalStateException(source + " '" + zone + "' is not a time zone", e);
        }
    }
}
