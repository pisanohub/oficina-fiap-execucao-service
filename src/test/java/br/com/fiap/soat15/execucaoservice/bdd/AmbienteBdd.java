package br.com.fiap.soat15.execucaoservice.bdd;

import br.com.fiap.soat15.execucaoservice.ExecucaoServiceApplication;
import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;

/**
 * Ambiente dos cenários BDD: DynamoDB Local e ElasticMQ em containers e a aplicação completa rodando
 * no mesmo processo, com consumidor e publicador ligados. É iniciado uma única vez para todos os cenários.
 */
final class AmbienteBdd {
    private static final String TABELA = "execucao_bdd";
    private static AmbienteBdd instancia;

    private final GenericContainer<?> dynamoLocal;
    private final GenericContainer<?> elasticMq;
    private final DynamoDbClient dynamo;
    private final SqsClient sqs;
    private final String urlComandos;
    private final String urlResultados;
    private final ConfigurableApplicationContext aplicacao;
    private final int portaApi;

    static synchronized AmbienteBdd obter() {
        if (instancia == null) {
            instancia = new AmbienteBdd();
        }
        return instancia;
    }

    private AmbienteBdd() {
        verificarDocker();
        dynamoLocal = new GenericContainer<>("amazon/dynamodb-local:latest")
                .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory").withExposedPorts(8000);
        elasticMq = new GenericContainer<>("softwaremill/elasticmq-native:latest").withExposedPorts(9324);
        dynamoLocal.start();
        elasticMq.start();

        String urlDynamo = "http://" + dynamoLocal.getHost() + ":" + dynamoLocal.getMappedPort(8000);
        String urlSqs = "http://" + elasticMq.getHost() + ":" + elasticMq.getMappedPort(9324);
        var credenciais = StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));
        dynamo = DynamoDbClient.builder().region(Region.US_EAST_1).credentialsProvider(credenciais)
                .endpointOverride(URI.create(urlDynamo)).build();
        sqs = SqsClient.builder().region(Region.US_EAST_1).credentialsProvider(credenciais)
                .endpointOverride(URI.create(urlSqs)).build();

        criarTabela();
        sqs.createQueue(b -> b.queueName("execucao-comandos"));
        sqs.createQueue(b -> b.queueName("execucao-resultados"));
        urlComandos = urlSqs + "/000000000000/execucao-comandos";
        urlResultados = urlSqs + "/000000000000/execucao-resultados";

        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        portaApi = portaLivre();
        // Argumentos de linha de comando têm a maior precedência e vencem os valores do application.yaml.
        aplicacao = new SpringApplicationBuilder(ExecucaoServiceApplication.class).run(
                "--spring.main.banner-mode=off",
                "--server.port=" + portaApi,
                "--management.server.port=" + portaLivre(),
                "--aws.region=us-east-1",
                "--aws.dynamodb-endpoint=" + urlDynamo,
                "--aws.sqs-endpoint=" + urlSqs,
                "--dynamodb.table-name=" + TABELA,
                "--sqs.execucao-comandos-url=" + urlComandos,
                "--sqs.execucao-resultados-url=" + urlResultados,
                "--sqs.consumer.enabled=true",
                "--sqs.consumer.poll-delay-ms=200",
                "--outbox.publisher.enabled=true",
                "--outbox.publisher.poll-delay-ms=200");
        Runtime.getRuntime().addShutdownHook(new Thread(this::encerrar));
    }

    private void verificarDocker() {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            return;
        }
        String mensagem = "Docker é necessário para os testes BDD (DynamoDB Local e ElasticMQ em containers).";
        if (System.getenv("CI") != null) {
            throw new IllegalStateException(mensagem);
        }
        Assumptions.abort(mensagem + " Cenários ignorados.");
    }

    private void criarTabela() {
        dynamo.createTable(CreateTableRequest.builder().tableName(TABELA).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());
    }

    private static int portaLivre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException erro) {
            throw new IllegalStateException("Não foi possível reservar uma porta livre", erro);
        }
    }

    private void encerrar() {
        try {
            aplicacao.close();
        } finally {
            dynamoLocal.stop();
            elasticMq.stop();
        }
    }

    String urlApi() {
        return "http://localhost:" + portaApi;
    }

    SqsClient sqs() {
        return sqs;
    }

    String urlComandos() {
        return urlComandos;
    }

    String urlResultados() {
        return urlResultados;
    }
}
