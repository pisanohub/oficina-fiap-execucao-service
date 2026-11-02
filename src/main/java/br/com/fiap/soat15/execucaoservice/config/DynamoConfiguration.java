package br.com.fiap.soat15.execucaoservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Clock;

@Configuration
public class DynamoConfiguration {
    @Bean
    DynamoDbClient dynamoDbClient(@Value("${aws.region}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
