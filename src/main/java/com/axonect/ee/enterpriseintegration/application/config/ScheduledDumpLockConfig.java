package com.axonect.ee.enterpriseintegration.application.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * The lock that decides which instance runs a firing of the scheduled dumps — see
 * {@link ScheduledDumpProperties.Lock}.
 *
 * <p>It is a row in Oracle, in the database the dumps' own requests are written to, so it adds no
 * dependency the job did not already have. Building it opens no connection; the table is first
 * touched at the first firing.
 */
@Configuration
public class ScheduledDumpLockConfig {

    @Bean
    public LockProvider scheduledDumpLockProvider(DataSource dataSource, ScheduledDumpProperties properties) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(properties.getLock().getTableName())
                // The database's clock, not each pod's: instances that disagree about the time
                // still agree about whether the lock is held.
                .usingDbTime()
                .build());
    }
}
