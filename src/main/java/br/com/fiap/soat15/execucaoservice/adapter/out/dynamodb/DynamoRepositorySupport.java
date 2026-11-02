package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;

import java.util.Map;

abstract class DynamoRepositorySupport {
    protected final DynamoDbClient dynamo;
    protected final String tableName;

    protected DynamoRepositorySupport(DynamoDbClient dynamo, String tableName) {
        this.dynamo = dynamo;
        this.tableName = tableName;
    }

    protected Map<String, AttributeValue> buscar(Map<String, AttributeValue> chave) {
        return dynamo.getItem(GetItemRequest.builder()
                .tableName(tableName).key(chave).consistentRead(true).build()).item();
    }

    protected TransactWriteItem putSeAusente(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder().put(Put.builder().tableName(tableName)
                .item(item).conditionExpression("attribute_not_exists(pk)").build()).build();
    }

    protected Map<String, AttributeValue> chaveExecucao(String osId) {
        return DynamoKeys.execucao(osId);
    }

    protected Map<String, AttributeValue> chaveCatalogo(TipoItem tipo, String itemId) {
        return DynamoKeys.catalogo(tipo, itemId);
    }

    protected AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }

    protected AttributeValue numero(long valor) {
        return AttributeValue.builder().n(Long.toString(valor)).build();
    }
}
