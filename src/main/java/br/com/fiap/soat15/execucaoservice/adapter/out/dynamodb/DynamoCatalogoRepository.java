package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.ItemCatalogoExistenteException;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


final class DynamoCatalogoRepository extends DynamoRepositorySupport {

    DynamoCatalogoRepository(DynamoDbClient dynamo, String tableName) {
        super(dynamo, tableName);
    }

    public void salvarItemCatalogo(ItemCatalogo catalogo) {
        Map<String, AttributeValue> item = new HashMap<>(chaveCatalogo(catalogo.tipo(), catalogo.itemId()));
        item.put("tipo", texto(catalogo.tipo().name()));
        item.put("itemId", texto(catalogo.itemId()));
        item.put("nome", texto(catalogo.nome()));
        item.put("precoUnitario", AttributeValue.builder().n(catalogo.precoUnitario().toPlainString()).build());
        item.put("ativo", AttributeValue.builder().bool(catalogo.ativo()).build());
        if (catalogo.quantidadeEstoque() != null) {
            item.put("quantidadeEstoque", numero(catalogo.quantidadeEstoque()));
        }
        try {
            dynamo.putItem(PutItemRequest.builder().tableName(tableName).item(item)
                    .conditionExpression("attribute_not_exists(pk)").build());
        } catch (ConditionalCheckFailedException existente) {
            throw new ItemCatalogoExistenteException(catalogo.itemId());
        }
    }

    public Optional<ItemCatalogo> buscarItemCatalogo(TipoItem tipo, String itemId) {
        Map<String, AttributeValue> item = buscar(chaveCatalogo(tipo, itemId));
        if (item.isEmpty()) {
            return Optional.empty();
        }
        Integer quantidade = item.containsKey("quantidadeEstoque")
                ? Integer.valueOf(item.get("quantidadeEstoque").n()) : null;
        return Optional.of(new ItemCatalogo(tipo, itemId, item.get("nome").s(),
                new BigDecimal(item.get("precoUnitario").n()), item.get("ativo").bool(), quantidade));
    }

}
