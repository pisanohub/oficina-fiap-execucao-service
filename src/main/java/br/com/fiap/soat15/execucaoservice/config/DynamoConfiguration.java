package br.com.fiap.soat15.execucaoservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Clock;

@Configuration
@EnableScheduling
public class DynamoConfiguration {
    @Bean
    DynamoDbClient dynamoDbClient(@Value("${aws.region}") String region,
                                  @Value("${aws.dynamodb-endpoint:}") String endpoint) {
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
