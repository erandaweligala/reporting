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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Read-only Elasticsearch client used to aggregate CDR usage for the user data dump.
 *
 * <p>It mirrors the connection settings of the cdr-service producer that writes those documents,
 * but is deliberately a separate, small pool: the dump is a batch reader and must not compete for
 * the connections the ingest path relies on. The synchronous client is the right one here — the
 * dump consumes aggregation pages in lockstep with the JDBC cursor it is merged against, so there
 * is nothing for an async client to overlap.
 */
@Configuration
@ConditionalOnProperty(prefix = "report.user-dump.usage", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ElasticsearchConfig {

    @Value("${elasticsearch.host:localhost}")
    private String host;

    @Value("${elasticsearch.port:9200}")
    private int port;

    @Value("${elasticsearch.protocol:http}")
    private String protocol;

    @Value("${elasticsearch.username:}")
    private String username;

    @Value("${elasticsearch.password:}")
    private String password;

    @Value("${elasticsearch.pool.max-conn-total:20}")
    private int maxConnTotal;

    @Value("${elasticsearch.pool.max-conn-per-route:20}")
    private int maxConnPerRoute;

    @Value("${elasticsearch.pool.io-thread-count:2}")
    private int ioThreadCount;

    @Value("${elasticsearch.pool.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    // Aggregating a full day of sessions can take a while to answer; the socket timeout has to
    // outlast the slowest aggregation page rather than the fastest.
    @Value("${elasticsearch.pool.socket-timeout-ms:120000}")
    private int socketTimeoutMs;

    @Value("${elasticsearch.pool.connection-request-timeout-ms:5000}")
    private int connectionRequestTimeoutMs;

    @Bean(destroyMethod = "close")
    public RestClient reportElasticsearchRestClient() {
        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, protocol));

        IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                .setIoThreadCount(ioThreadCount)
                .setSoKeepAlive(true)
                .build();

        BasicCredentialsProvider credentialsProvider = null;
        if (username != null && !username.isEmpty()) {
            credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));
        }

        final BasicCredentialsProvider resolvedCredentials = credentialsProvider;
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            httpClientBuilder
                    .setMaxConnTotal(maxConnTotal)
                    .setMaxConnPerRoute(maxConnPerRoute)
                    .setDefaultIOReactorConfig(ioReactorConfig);
            if (resolvedCredentials != null) {
                httpClientBuilder.setDefaultCredentialsProvider(resolvedCredentials);
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
