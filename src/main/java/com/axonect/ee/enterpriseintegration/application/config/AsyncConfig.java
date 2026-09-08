package com.axonect.ee.enterpriseintegration.application.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${report.max-concurrent:5}")
    private int maxConcurrentReports;

    // Lightweight pool just for dispatching — slot check + status update
    @Bean("reportDispatchExecutor")
    public Executor reportDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("report-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Threads the user data dump splits its username shards over. It is kept apart from
     * reportExecutor deliberately: that pool holds one slot per running report, and a dump that
     * borrowed slots from it for its own shards would starve every other report while it ran.
     */
    @Bean(name = "userDumpExecutor")
    public Executor userDumpExecutor(
            @Value("${report.user-dump.shards:4}") int shards,
            @Value("${report.user-dump.worker-threads:0}") int workerThreads) {

        int threads = workerThreads > 0 ? workerThreads : Math.max(1, shards);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        // Shards are submitted in one burst per report and the submitting thread runs one of them
        // itself, so the queue only has to hold the remainder of a few concurrent dumps.
        executor.setQueueCapacity(threads * 4);
        executor.setThreadNamePrefix("user-dump-shard-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "reportExecutor")
    public Executor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("report-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}