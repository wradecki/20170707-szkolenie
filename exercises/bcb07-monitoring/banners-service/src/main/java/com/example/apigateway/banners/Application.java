package com.example.apigateway.banners;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
