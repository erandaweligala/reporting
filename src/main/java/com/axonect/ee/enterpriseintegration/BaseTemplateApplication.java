package com.axonect.ee.enterpriseintegration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;

@Import(com.adl.et.telco.dte.prom_spring_metrics_plugin.domain.metrics.autoconfigure.PromMetricsAutoConfiguration.class)
@SpringBootApplication
@EnableAsync
public class BaseTemplateApplication {
    public static void main(String[] args) {
        SpringApplication.run(BaseTemplateApplication.class, args);
    }
}
