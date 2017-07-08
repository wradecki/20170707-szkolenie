package com.example.apigateway.gateway;

import net.jodah.failsafe.CircuitBreaker;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class GatewayApp {

    /*
        * Authentication
        * Failure handling
        * Simple load-balancing
        * Simple heart-beat check
    */

    public static void main(String[] args) {
        SpringApplication.run(GatewayApp.class, args);
    }

    @Bean
    RestTemplate rest() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(1500);
        factory.setConnectTimeout(1500);

        return new RestTemplate(factory);
    }

    @Bean
    Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(40);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ApiGatewayLookup-");
        executor.initialize();
        return executor;
    }

    @Bean
    byte[] defaultBanner() throws IOException {
        InputStream stream = GatewayApp.class.getResource("/default-banner.png").openStream();
        return StreamUtils.copyToByteArray(stream);
    }

}

@RestController
class Controllers {

    private static Logger logger = LoggerFactory.getLogger(Controllers.class);

    @Autowired
    RestTemplate rest;

    @Autowired
    Executor exec;

    @Autowired
    byte[] defaultBanner;

    CircuitBreaker bannersCb = new CircuitBreaker()
            .withDelay(1, TimeUnit.MINUTES)
            .withFailureThreshold(2);


    @RequestMapping(value = "/banners", produces = MediaType.IMAGE_PNG_VALUE)
    public Future<byte[]> getBanners() {
        final String bannersUrl = "http://localhost:8081/";

        RetryPolicy rt = new RetryPolicy()
                .withDelay(5, TimeUnit.SECONDS)
                .withMaxRetries(2)
                .retryOn(Exception.class);

        return CompletableFuture.supplyAsync(()
                    -> Failsafe.with(bannersCb).with(rt)
                        .onFailure(t -> logger.warn("Connection error: ", t))
                        .withFallback(defaultBanner)
                        .get(()
                                -> rest.getForObject(bannersUrl, byte[].class)), exec);
    }

    @RequestMapping(value = "/api/**", produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<String>> legacyTodos(@RequestBody(required = false) String payload,
                                                                final HttpServletRequest request) {
        final String legacyApiUrl = "http://localhost:8080/";

        final String method = request.getMethod();
        final String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);

        return CompletableFuture.supplyAsync(() -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(payload, headers);

            return rest.exchange(legacyApiUrl + path, HttpMethod.resolve(method), entity, String.class);
        }, exec);
    }

}