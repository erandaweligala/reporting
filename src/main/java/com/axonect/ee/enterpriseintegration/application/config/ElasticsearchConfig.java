package com.axonect.ee.enterpriseintegration.application.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch client for report generation, pointed at the same cluster cdr-service writes
 * its {@code radius-sessions-*} indices to.
 *
 * <p>Mirrors cdr-service's {@code ElasticsearchProducer} (host/port/protocol/basic-auth and the
 * same pool knobs) but produces the <em>synchronous</em> client: report generation runs on a
 * bounded worker pool and consumes each aggregation page before asking for the next, so the
 * async client would only add callback plumbing around what is already a blocking pipeline.
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${elasticsearch.host:localhost}")
    private String serverHost;

    @Value("${elasticsearch.port:9200}")
    private int serverPort;

    @Value("${elasticsearch.protocol:http}")
    private String protocol;

    @Value("${elasticsearch.username:}")
    private String userName;

    @Value("${elasticsearch.password:}")
    private String password;

    @Value("${elasticsearch.pool.max-conn-total:50}")
    private int maxConnTotal;

    @Value("${elasticsearch.pool.max-conn-per-route:50}")
    private int maxConnPerRoute;

    @Value("${elasticsearch.pool.io-thread-count:2}")
    private int ioThreadCount;

    @Value("${elasticsearch.pool.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    /**
     * Generous by default: a composite aggregation page over a full day of sessions is a much
     * heavier request than the single-document reads cdr-service issues.
     */
    @Value("${elasticsearch.pool.socket-timeout-ms:120000}")
    private int socketTimeoutMs;

    @Value("${elasticsearch.pool.connection-request-timeout-ms:5000}")
    private int connectionRequestTimeoutMs;

    @Bean(destroyMethod = "close")
    public RestClient reportElasticsearchRestClient() {
        RestClientBuilder builder = RestClient.builder(new HttpHost(serverHost, serverPort, protocol));

        IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                .setIoThreadCount(ioThreadCount)
                .setSoKeepAlive(true)
                .build();

        BasicCredentialsProvider credentialsProvider = null;
        if (userName != null && !userName.isEmpty()) {
            credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(userName, password)
            );
        }

        final BasicCredentialsProvider finalCredentialsProvider = credentialsProvider;
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            httpClientBuilder
                    .setMaxConnTotal(maxConnTotal)
                    .setMaxConnPerRoute(maxConnPerRoute)
                    .setDefaultIOReactorConfig(ioReactorConfig);
            if (finalCredentialsProvider != null) {
                httpClientBuilder.setDefaultCredentialsProvider(finalCredentialsProvider);
            }
            return httpClientBuilder;
        });

        builder.setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                .setConnectTimeout(connectTimeoutMs)
                .setSocketTimeout(socketTimeoutMs)
                .setConnectionRequestTimeout(connectionRequestTimeoutMs));

        return builder.build();
    }

    @Bean
    public ElasticsearchClient reportElasticsearchClient(RestClient reportElasticsearchRestClient) {
        return new ElasticsearchClient(
                new RestClientTransport(reportElasticsearchRestClient, new JacksonJsonpMapper()));
    }
}
