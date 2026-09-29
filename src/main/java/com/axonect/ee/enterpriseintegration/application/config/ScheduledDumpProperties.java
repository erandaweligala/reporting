package com.axonect.ee.enterpriseintegration.application.config;

import com.axonect.ee.enterpriseintegration.domain.constant.TableExtracts;
import com.axonect.ee.enterpriseintegration.domain.service.impl.UserDataDumpReportDefinition;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * When the nightly dumps are generated without anyone asking for them.
 *
 * <p>A scheduled run is not a second way of producing a dump. At each firing one report request
 * per entry of {@link #reportTypes} is created exactly as {@code POST /api/report-download/create}
 * creates one — a REPORT_DOWNLOAD row, the same concurrency cap, the same output directory, the
 * same download endpoint — so a file the scheduler wrote is found, listed and downloaded like any
 * other, under {@link #createdBy}.
 *
 * <p>Everything here is read at startup. Switching the job on or off, or moving it, is a change of
 * configuration and a restart, not of code.
 */
@Component
@ConfigurationProperties(prefix = "report.scheduled-dump")
@Data
public class ScheduledDumpProperties {

    /**
     * Whether the job is registered at all.
     *
     * <p>Off in code, so that a deployment which has not configured the job never starts a
     * multi-million row extract at a time nobody chose; the shipped application.yml switches it on.
     */
    private boolean enabled = false;

    /**
     * Spring cron expression — second, minute, hour, day of month, month, day of week — for when
     * the dumps are requested. The default is every day at 00:30.
     */
    private String cron = "0 30 0 * * *";

    /**
     * Zone {@link #cron} is read in. Empty, the default, is {@code report.user-dump.timezone}: the
     * zone USER_DATA_DUMP resolves its reported day in, so a run at 00:30 reports on the day that
     * has just ended rather than on whichever day it is in the server's own zone.
     */
    private String zone = "";

    /** Report types requested at each firing, in the order they are requested. */
    private List<String> reportTypes = new ArrayList<>(List.of(
            UserDataDumpReportDefinition.REPORT_TYPE,
            TableExtracts.MAC_SERVICE_TABLE_TYPE,
            TableExtracts.PLAN_TO_BUCKET_TYPE,
            TableExtracts.BUCKET_INSTANCE_TYPE));

    /** CREATED_BY recorded on every request the job creates. */
    private String createdBy = "SCHEDULER";

    /** Which of several instances runs a firing. */
    private Lock lock = new Lock();

    /**
     * Every instance with the job enabled fires it, so without this each would file its own four
     * requests. At each firing the instances race for one row of {@link #tableName} in Oracle; the
     * one that takes it files the requests and the others log that they stood down. The race is a
     * single statement per instance per firing, and the lock times are taken from the database's
     * clock rather than the pods', so instances whose clocks disagree still see the same lock.
     */
    @Data
    public static class Lock {

        /**
         * Off only for a deployment that runs one instance and has no lock table: every instance
         * then runs every firing.
         */
        private boolean enabled = true;

        /** The ShedLock table. See docs/scheduled-dumps.md for its DDL. */
        private String tableName = "SHEDLOCK";

        /**
         * How long the lock is held however quickly the firing finishes. Filing four requests takes
         * well under a second, so this is what keeps an instance that fires late — its scheduler
         * thread busy with the watchdog at 00:30 — from finding the lock free and firing again. It
         * has to be shorter than the time between two firings.
         */
        private Duration atLeastFor = Duration.ofMinutes(10);

        /**
         * How long the lock is held at most, should the instance holding it die before letting it
         * go. It is not how long the dumps may run: the lock covers filing the requests, not
         * generating the files.
         */
        private Duration atMostFor = Duration.ofMinutes(30);
    }
}
