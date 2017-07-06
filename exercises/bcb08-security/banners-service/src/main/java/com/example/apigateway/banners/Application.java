package com.example.apigateway.banners;

import brave.Tracing;
import brave.context.slf4j.MDCCurrentTraceContext;
import brave.sampler.Sampler;
import brave.sparkjava.SparkTracing;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import spark.ExceptionHandlerImpl;
import zipkin.reporter.AsyncReporter;
import zipkin.reporter.Encoding;
import zipkin.reporter.urlconnection.URLConnectionSender;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static spark.Spark.*;

public class Application {

    private static Logger log = LoggerFactory.getLogger(Application.class);
    private static final int PORT = 8081;

    public static void main(String[] args) {
        //setup Eureka
        MyDataCenterInstanceConfig instanceConfig = new MyDataCenterInstanceConfig();
        InstanceInfo instanceInfo = new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get();
        ApplicationInfoManager applicationInfoManager = new ApplicationInfoManager(instanceConfig, instanceInfo);
        applicationInfoManager.setInstanceStatus(InstanceInfo.InstanceStatus.STARTING);
        new DiscoveryClient(applicationInfoManager, new DefaultEurekaClientConfig());

        //setup SparkJava application
        port(PORT);
        staticFileLocation("/webapp");

        //setup Zipin / Brave (tracing)
        URLConnectionSender sender = URLConnectionSender.builder()
                .encoding(Encoding.JSON)
                .endpoint("http://localhost:9411/api/v1/spans")
                .build();

        SparkTracing tracing = SparkTracing.create(Tracing
                .newBuilder()
                .currentTraceContext(MDCCurrentTraceContext.create())
                .localServiceName(instanceConfig.getAppname())
                .reporter(AsyncReporter.builder(sender).build())
                .build());
        before(tracing.before());
        exception(Exception.class, tracing.exception((exception, request, response) -> {
            exception.printStackTrace();
            internalServerError((req, res) -> {
                res.type("application/json");
                return "{\"message\":\"Custom 500 handling\"}";
            });
        }));
        afterAfter(tracing.afterAfter());


        get("/", (req, resp) -> {
            URI rootFolder = Application.class.getResource("/webapp").toURI();
            List<Path> banners = Files.list(Paths.get(rootFolder))
                    .collect(Collectors.toList());

            Random rand = new Random();
            Path path = banners.get(rand.nextInt(banners.size()));

            log.info("Random image: {}", path.getFileName());
            byte[] bytes = Files.readAllBytes(path);

            HttpServletResponse raw = resp.raw();
            raw.setContentLength(bytes.length);
            raw.setContentType("image/png");
            ServletOutputStream stream = raw.getOutputStream();
            stream.write(bytes);

            return raw;
        });

        //wait for SparkJava application to initialize
        awaitInitialization();

        //change Eureka status to UP (from STARTING)
        applicationInfoManager.setInstanceStatus(InstanceInfo.InstanceStatus.UP);
    }

}
