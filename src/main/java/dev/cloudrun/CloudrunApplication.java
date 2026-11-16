package dev.cloudrun;

import dev.cloudrun.config.CloudrunProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CloudrunProperties.class)
public class CloudrunApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudrunApplication.class, args);
    }
}
