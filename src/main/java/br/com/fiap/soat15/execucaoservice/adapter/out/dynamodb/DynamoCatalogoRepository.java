package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.ItemCatalogoExistenteException;
import br.com.fiap.soat15.execucaoservice.application.ItemCatalogoNaoEncontradoException;
import br.com.fiap.soat15.execucaoservice.domain.model.AtualizacaoItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
        if (catalogo.descricao() != null) {
            item.put("descricao", texto(catalogo.descricao()));
        }
        if (catalogo.tempoEstimadoMinutos() != null) {
            item.put("tempoEstimadoMinutos", numero(catalogo.tempoEstimadoMinutos()));
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
        return Optional.of(mapear(tipo, itemId, item));
    }

    public List<ItemCatalogo> listarItensCatalogo(TipoItem tipo, Boolean ativo) {
        StringBuilder filtro = new StringBuilder("sk = :sk");
        Map<String, AttributeValue> valores = new HashMap<>();
        valores.put(":sk", texto(DynamoKeys.CADASTRO));
        if (tipo != null) {
            filtro.append(" AND begins_with(pk, :prefixo)");
            valores.put(":prefixo", texto(tipo.name() + "#"));
        }
        if (ativo != null) {
            filtro.append(" AND ativo = :ativo");
            valores.put(":ativo", AttributeValue.builder().bool(ativo).build());
        }
        List<ItemCatalogo> resultado = new ArrayList<>();
        Map<String, AttributeValue> cursor = Map.of();
        do {
            ScanRequest.Builder request = ScanRequest.builder().tableName(tableName)
                    .filterExpression(filtro.toString()).expressionAttributeValues(valores);
            if (cursor != null && !cursor.isEmpty()) request.exclusiveStartKey(cursor);
            var pagina = dynamo.scan(request.build());
            for (var item : pagina.items()) {
                resultado.add(mapear(TipoItem.valueOf(item.get("tipo").s()), item.get("itemId").s(), item));
            }
            cursor = pagina.lastEvaluatedKey();
        } while (cursor != null && !cursor.isEmpty());
        resultado.sort(Comparator.comparing((ItemCatalogo item) -> item.tipo().name()).thenComparing(ItemCatalogo::itemId));
        return resultado;
    }

    public ItemCatalogo atualizarItemCatalogo(TipoItem tipo, String itemId, AtualizacaoItemCatalogo atualizacao) {
        // Não toca em quantidadeEstoque: o estoque só muda por reserva, devolução, reposição ou ajuste.
        // Campos opcionais ausentes na atualização são removidos (a atualização substitui os dados do item).
        List<String> definicoes = new ArrayList<>(List.of("nome = :nome", "precoUnitario = :preco", "ativo = :ativo"));
        List<String> remocoes = new ArrayList<>();
        Map<String, AttributeValue> valores = new HashMap<>();
        valores.put(":nome", texto(atualizacao.nome()));
        valores.put(":preco", AttributeValue.builder().n(atualizacao.precoUnitario().toPlainString()).build());
        valores.put(":ativo", AttributeValue.builder().bool(atualizacao.ativo()).build());
        if (atualizacao.descricao() != null) {
            definicoes.add("descricao = :descricao");
            valores.put(":descricao", texto(atualizacao.descricao()));
        } else {
            remocoes.add("descricao");
        }
        if (atualizacao.tempoEstimadoMinutos() != null) {
            definicoes.add("tempoEstimadoMinutos = :tempo");
            valores.put(":tempo", numero(atualizacao.tempoEstimadoMinutos()));
        } else {
            remocoes.add("tempoEstimadoMinutos");
        }
        String expressao = "SET " + String.join(", ", definicoes)
                + (remocoes.isEmpty() ? "" : " REMOVE " + String.join(", ", remocoes));
        return atualizar(tipo, itemId, expressao, "attribute_exists(pk)", valores);
    }

    public void inativarItemCatalogo(TipoItem tipo, String itemId) {
        atualizar(tipo, itemId, "SET ativo = :ativo", "attribute_exists(pk)",
                Map.of(":ativo", AttributeValue.builder().bool(false).build()));
    }

    public ItemCatalogo reporEstoque(String itemId, int quantidade) {
        return atualizar(TipoItem.PECA, itemId, "SET quantidadeEstoque = quantidadeEstoque + :quantidade",
                "attribute_exists(pk) AND attribute_exists(quantidadeEstoque)",
                Map.of(":quantidade", numero(quantidade)));
    }

    public ItemCatalogo ajustarEstoque(String itemId, int quantidadeEstoque) {
        return atualizar(TipoItem.PECA, itemId, "SET quantidadeEstoque = :quantidade",
                "attribute_exists(pk) AND attribute_exists(quantidadeEstoque)",
                Map.of(":quantidade", numero(quantidadeEstoque)));
    }

    private ItemCatalogo atualizar(TipoItem tipo, String itemId, String expressao, String condicao,
                                   Map<String, AttributeValue> valores) {
        try {
            var resposta = dynamo.updateItem(UpdateItemRequest.builder().tableName(tableName)
                    .key(chaveCatalogo(tipo, itemId)).updateExpression(expressao)
                    .conditionExpression(condicao).expressionAttributeValues(valores)
                    .returnValues(ReturnValue.ALL_NEW).build());
            return mapear(tipo, itemId, resposta.attributes());
        } catch (ConditionalCheckFailedException inexistente) {
            throw new ItemCatalogoNaoEncontradoException(tipo, itemId);
        }
    }

    private ItemCatalogo mapear(TipoItem tipo, String itemId, Map<String, AttributeValue> item) {
        Integer quantidade = item.containsKey("quantidadeEstoque")
                ? Integer.valueOf(item.get("quantidadeEstoque").n()) : null;
        String descricao = item.containsKey("descricao") ? item.get("descricao").s() : null;
        Integer tempoEstimado = item.containsKey("tempoEstimadoMinutos")
                ? Integer.valueOf(item.get("tempoEstimadoMinutos").n()) : null;
        return new ItemCatalogo(tipo, itemId, item.get("nome").s(), descricao,
                new BigDecimal(item.get("precoUnitario").n()), item.get("ativo").bool(), quantidade, tempoEstimado);
    }

}
