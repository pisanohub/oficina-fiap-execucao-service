package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.DiagnosticoNaoEncontradoException;
import br.com.fiap.soat15.execucaoservice.application.ExecucaoRepository;
import br.com.fiap.soat15.execucaoservice.application.ItemCatalogoExistenteException;
import br.com.fiap.soat15.execucaoservice.application.VersaoDiagnosticoConflitanteException;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DynamoExecucaoRepository implements ExecucaoRepository {
    private final DynamoDbClient dynamo;
    private final String tableName;

    public DynamoExecucaoRepository(DynamoDbClient dynamo, @Value("${dynamodb.table-name}") String tableName) {
        this.dynamo = dynamo;
        this.tableName = tableName;
    }

    @Override
    public Diagnostico iniciarDiagnostico(String osId, Instant agora) {
        Optional<Diagnostico> existente = buscarDiagnostico(osId);
        if (existente.isPresent()) {
            return existente.get();
        }
        Map<String, AttributeValue> item = new HashMap<>(chaveDiagnostico(osId));
        item.put("status", texto("EM_DIAGNOSTICO"));
        item.put("versao", numero(1));
        item.put("itens", AttributeValue.builder().l(List.of()).build());
        item.put("atualizadoEm", texto(agora.toString()));
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pk)")
                    .build());
            return new Diagnostico(osId, 1, List.of(), agora);
        } catch (ConditionalCheckFailedException corrida) {
            return buscarDiagnostico(osId).orElseThrow(() -> corrida);
        }
    }

    @Override
    public Diagnostico atualizarItens(String osId, long versaoEsperada, List<ItemSolicitado> itens, Instant agora) {
        List<AttributeValue> itensDynamo = itens.stream().map(item -> AttributeValue.builder().m(Map.of(
                "tipo", texto(item.tipo().name()),
                "itemId", texto(item.itemId()),
                "quantidade", numero(item.quantidade())
        )).build()).toList();
        try {
            UpdateItemResponse response = dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(chaveDiagnostico(osId))
                    .updateExpression("SET #itens = :itens, #versao = :nova, #atualizado = :agora")
                    .conditionExpression("#versao = :esperada AND #status = :diagnostico")
                    .expressionAttributeNames(Map.of(
                            "#itens", "itens", "#versao", "versao", "#atualizado", "atualizadoEm", "#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":itens", AttributeValue.builder().l(itensDynamo).build(),
                            ":nova", numero(versaoEsperada + 1),
                            ":esperada", numero(versaoEsperada),
                            ":agora", texto(agora.toString()),
                            ":diagnostico", texto("EM_DIAGNOSTICO")))
                    .returnValues("ALL_NEW")
                    .build());
            return mapearDiagnostico(response.attributes());
        } catch (ConditionalCheckFailedException conflito) {
            if (buscarDiagnostico(osId).isEmpty()) {
                throw new DiagnosticoNaoEncontradoException(osId);
            }
            throw new VersaoDiagnosticoConflitanteException(osId);
        }
    }

    @Override
    public Optional<Diagnostico> buscarDiagnostico(String osId) {
        Map<String, AttributeValue> item = buscar(chaveDiagnostico(osId));
        return item.isEmpty() ? Optional.empty() : Optional.of(mapearDiagnostico(item));
    }

    @Override
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

    @Override
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

    private Map<String, AttributeValue> buscar(Map<String, AttributeValue> chave) {
        return dynamo.getItem(GetItemRequest.builder()
                .tableName(tableName).key(chave).consistentRead(true).build()).item();
    }

    private Diagnostico mapearDiagnostico(Map<String, AttributeValue> item) {
        List<ItemSolicitado> itens = new ArrayList<>();
        for (AttributeValue valor : item.get("itens").l()) {
            Map<String, AttributeValue> campos = valor.m();
            itens.add(new ItemSolicitado(TipoItem.valueOf(campos.get("tipo").s()),
                    campos.get("itemId").s(), Integer.parseInt(campos.get("quantidade").n())));
        }
        String osId = item.get("pk").s().substring("OS#".length());
        return new Diagnostico(osId, Long.parseLong(item.get("versao").n()), item.get("status").s(), itens,
                Instant.parse(item.get("atualizadoEm").s()));
    }

    private Map<String, AttributeValue> chaveDiagnostico(String osId) {
        return Map.of("pk", texto("OS#" + osId), "sk", texto("DIAGNOSTICO"));
    }

    private Map<String, AttributeValue> chaveCatalogo(TipoItem tipo, String itemId) {
        return Map.of("pk", texto(tipo.name() + "#" + itemId), "sk", texto("CADASTRO"));
    }

    private AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }

    private AttributeValue numero(long valor) {
        return AttributeValue.builder().n(Long.toString(valor)).build();
    }
}
