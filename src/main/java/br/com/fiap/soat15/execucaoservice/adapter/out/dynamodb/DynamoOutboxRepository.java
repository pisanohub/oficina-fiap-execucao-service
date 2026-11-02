package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.OutboxEvent;
import br.com.fiap.soat15.execucaoservice.application.OutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class DynamoOutboxRepository implements OutboxRepository {
    private final DynamoDbClient dynamo;
    private final String tableName;

    public DynamoOutboxRepository(DynamoDbClient dynamo, @Value("${dynamodb.table-name}") String tableName) {
        this.dynamo = dynamo;
        this.tableName = tableName;
    }

    @Override
    public List<OutboxEvent> pendentes(int limite) {
        List<OutboxEvent> eventos = new ArrayList<>();
        if (limite <= 0) return eventos;
        Map<String, AttributeValue> cursor = Map.of();
        do {
            ScanRequest.Builder requisicao = ScanRequest.builder().tableName(tableName)
                    .filterExpression("begins_with(pk, :prefixo) AND #published = :false")
                    .expressionAttributeNames(Map.of("#published", "published"))
                    .expressionAttributeValues(Map.of(":prefixo", texto("OUTBOX#"),
                            ":false", AttributeValue.builder().bool(false).build()))
                    .limit(100);
            if (!cursor.isEmpty()) requisicao.exclusiveStartKey(cursor);
            ScanResponse pagina = dynamo.scan(requisicao.build());
            for (Map<String, AttributeValue> item : pagina.items()) {
                eventos.add(mapear(item));
                if (eventos.size() == limite) return eventos;
            }
            cursor = pagina.lastEvaluatedKey();
        } while (cursor != null && !cursor.isEmpty());
        return eventos;
    }

    @Override
    public boolean reservar(OutboxEvent evento, String dono, Instant agora, Instant ate) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder().tableName(tableName).key(chave(evento))
                    .updateExpression("SET leaseOwner = :dono, leaseUntil = :ate")
                    .conditionExpression("#published = :false AND (attribute_not_exists(leaseUntil) OR leaseUntil < :agora)")
                    .expressionAttributeNames(Map.of("#published", "published"))
                    .expressionAttributeValues(Map.of(":dono", texto(dono),
                            ":ate", numero(ate.toEpochMilli()), ":agora", numero(agora.toEpochMilli()),
                            ":false", AttributeValue.builder().bool(false).build())).build());
            return true;
        } catch (ConditionalCheckFailedException ocupado) {
            return false;
        }
    }

    @Override
    public void marcarPublicado(OutboxEvent evento, String dono, Instant agora) {
        dynamo.updateItem(UpdateItemRequest.builder().tableName(tableName).key(chave(evento))
                .updateExpression("SET #published = :true, publishedAt = :agora REMOVE leaseOwner, leaseUntil")
                .conditionExpression("#published = :false AND leaseOwner = :dono")
                .expressionAttributeNames(Map.of("#published", "published"))
                .expressionAttributeValues(Map.of(":true", AttributeValue.builder().bool(true).build(),
                        ":false", AttributeValue.builder().bool(false).build(),
                        ":agora", texto(agora.toString()), ":dono", texto(dono))).build());
    }

    private OutboxEvent mapear(Map<String, AttributeValue> item) {
        return new OutboxEvent(item.get("operationId").s(), item.get("type").s(),
                item.get("sagaId").s(), item.get("osId").s(), item.get("causationId").s(),
                Instant.parse(item.get("occurredAt").s()), item.get("orcamentoId").s(),
                Integer.parseInt(item.get("versaoOrcamento").n()), item.get("pagamentoId").s());
    }

    private Map<String, AttributeValue> chave(OutboxEvent evento) {
        return Map.of("pk", texto("OUTBOX#" + evento.operationId()), "sk", texto("RESULTADO"));
    }

    private AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }

    private AttributeValue numero(long valor) {
        return AttributeValue.builder().n(Long.toString(valor)).build();
    }
}
