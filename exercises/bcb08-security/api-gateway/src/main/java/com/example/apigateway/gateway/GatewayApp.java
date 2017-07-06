package com.example.apigateway.gateway;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.keycloak.KeycloakPrincipal;
import org.keycloak.adapters.springsecurity.KeycloakSecurityComponents;
import org.keycloak.adapters.springsecurity.authentication.KeycloakAuthenticationProvider;
import org.keycloak.adapters.springsecurity.config.KeycloakWebSecurityConfigurerAdapter;
import org.keycloak.adapters.springsecurity.token.KeycloakAuthenticationToken;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.sleuth.Sampler;
import org.springframework.cloud.sleuth.instrument.async.LazyTraceExecutor;
import org.springframework.cloud.sleuth.sampler.AlwaysSampler;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.mapping.SimpleAuthorityMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@EnableDiscoveryClient
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
    @LoadBalanced
    RestTemplate rest() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(3000);
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

@Configuration
class TracesConfig {

    @Bean
    public Sampler defaultSampler() {
        return new AlwaysSampler();
    }

    @Bean
    public LazyTraceExecutor traceExecutor(BeanFactory bf, Executor exec) {
        return new LazyTraceExecutor(bf, exec);
    }

}

@EnableWebSecurity
@ComponentScan(basePackageClasses = KeycloakSecurityComponents.class)
class SecurityConfig extends KeycloakWebSecurityConfigurerAdapter {
    @Autowired
    public void configureGlobal(AuthenticationManagerBuilder auth) throws Exception {
        KeycloakAuthenticationProvider keycloakAuthenticationProvider = keycloakAuthenticationProvider();
        keycloakAuthenticationProvider.setGrantedAuthoritiesMapper(new SimpleAuthorityMapper());
        auth.authenticationProvider(keycloakAuthenticationProvider);
    }

    @Bean
    @Override
    protected SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new RegisterSessionAuthenticationStrategy(new SessionRegistryImpl());
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        http
                .csrf().disable()
                .authorizeRequests()
                .antMatchers("*.html").permitAll()
                .anyRequest().authenticated();
    }

    @Bean
    @Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
    KeycloakPrincipal principal() {
        KeycloakAuthenticationToken token = (KeycloakAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        return (KeycloakPrincipal) token.getPrincipal();
    }
}


@RestController
class Controllers {

    @Autowired
    RestTemplate rest;

    @Autowired
    LazyTraceExecutor exec;

    @Autowired
    byte[] defaultBanner;

    @RequestMapping(value = "/banners", produces = MediaType.IMAGE_PNG_VALUE)
    public CompletableFuture<byte[]> getBanners() {
        final String bannersUrl = "http://banners/";

        final RetryPolicy rt = new RetryPolicy()
                .retryOn(Exception.class)
                .withDelay(10, TimeUnit.SECONDS)
                .withMaxRetries(2);

        return CompletableFuture.supplyAsync(() -> Failsafe.with(rt)
                .withFallback(defaultBanner)
                .get(() -> rest.getForObject(bannersUrl, byte[].class)), exec);
    }

    @RequestMapping(method = {RequestMethod.PUT, RequestMethod.POST},
            value = "/api/**", produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<String>> profanityProxy(@RequestBody(required = false) String payload,
                                                                    final HttpServletRequest request) {

        final String profanityProxy = "http://profanity/";

        final String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);

        URI uri = UriComponentsBuilder.fromHttpUrl(profanityProxy)
                .path(path).build().toUri();

        return makeCall(uri, payload.toString(), request);
    }

    @RequestMapping(value = "/api/**", produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<String>> legacyTodos(@RequestBody(required = false) String payload,
                                                                 final HttpServletRequest request) {

        final String legacyApiUrl = "http://legacy/";

        final String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);

        URI uri = UriComponentsBuilder.fromHttpUrl(legacyApiUrl)
                .path(path).build().toUri();


        return makeCall(uri, payload, request);
    }

    private CompletableFuture<ResponseEntity<String>> makeCall(URI uri, String payload, final HttpServletRequest request) {
        final String method = request.getMethod();

        return CompletableFuture.supplyAsync(() -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(payload, headers);

            return rest.exchange(uri, HttpMethod.resolve(method), entity, String.class);
        }, exec);

    }

}
