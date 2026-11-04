package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.DiagnosticoNaoEncontradoException;
import br.com.fiap.soat15.execucaoservice.application.VersaoDiagnosticoConflitanteException;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


final class DynamoDiagnosticoRepository extends DynamoRepositorySupport {

    DynamoDiagnosticoRepository(DynamoDbClient dynamo, String tableName) {
        super(dynamo, tableName);
    }

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

    public Optional<Diagnostico> buscarDiagnostico(String osId) {
        Map<String, AttributeValue> item = buscar(chaveDiagnostico(osId));
        return item.isEmpty() ? Optional.empty() : Optional.of(mapearDiagnostico(item));
    }

    private Diagnostico mapearDiagnostico(Map<String, AttributeValue> item) {
        List<ItemSolicitado> itens = new ArrayList<>();
        for (AttributeValue valor : item.get("itens").l()) {
            Map<String, AttributeValue> campos = valor.m();
            itens.add(new ItemSolicitado(TipoItem.valueOf(campos.get("tipo").s()),
                    campos.get("itemId").s(), Integer.parseInt(campos.get("quantidade").n())));
        }
        String osId = item.get("pk").s().substring(DynamoKeys.PREFIXO_OS.length());
        return new Diagnostico(osId, Long.parseLong(item.get("versao").n()), item.get("status").s(), itens,
                Instant.parse(item.get("atualizadoEm").s()));
    }

    private Map<String, AttributeValue> chaveDiagnostico(String osId) {
        return DynamoKeys.chaveDiagnostico(osId);
    }

}
