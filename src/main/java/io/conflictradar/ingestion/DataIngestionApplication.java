package io.conflictradar.ingestion;

import io.conflictradar.ingestion.config.RssConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableRetry
@EnableScheduling
@EnableConfigurationProperties(RssConfig.class)
@ConfigurationPropertiesScan
public class DataIngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataIngestionApplication.class, args);
    }
}
