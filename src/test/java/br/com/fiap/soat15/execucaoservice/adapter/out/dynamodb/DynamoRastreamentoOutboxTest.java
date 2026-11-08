package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.OutboxEvent;
import br.com.fiap.soat15.execucaoservice.config.observability.RastreamentoDeTeste;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static br.com.fiap.soat15.execucaoservice.config.observability.RastreamentoDeTeste.TRACEPARENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoRastreamentoOutboxTest {
    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);

    private static AttributeValue s(String valor) { return AttributeValue.builder().s(valor).build(); }

    private AgendarExecucaoCommand comando() {
        return new AgendarExecucaoCommand(1, "02539ee2-c754-4bb7-b178-4b328de9dbd5", "op-1", "AgendarExecucao",
                "saga-1", "os-1", "causa", "2026-11-02T14:00:00Z",
                new AgendarExecucaoCommand.Payload("orc-1", 1, "pag-1", List.of(new ItemSolicitado(TipoItem.SERVICO, "troca", 1))));
    }

    private Map<String, AttributeValue> itemDaOutbox(TransactWriteItemsRequest request) {
        return request.transactItems().stream().map(TransactWriteItem::put)
                .filter(put -> put != null && put.item().get("pk").s().startsWith("OUTBOX#"))
                .findFirst().orElseThrow().item();
    }

    @Test
    void gravaOContextoDoTraceJuntoComOEventoDaOutbox() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        var contexto = new RastreamentoDeTeste().contexto();
        var repository = new DynamoExecucaoRepository(dynamo, "execucao_service", contexto);

        try (var escopo = contexto.restaurar(Map.of("traceparent", TRACEPARENT))) {
            repository.agendarExecucao(comando(), Instant.parse("2026-11-02T14:00:00Z"));
        }

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(captor.capture());
        assertThat(itemDaOutbox(captor.getValue()).get("rastreamento").m().get("traceparent").s()).isEqualTo(TRACEPARENT);
    }

    @Test
    void semTraceAtivoNaoGravaOAtributo() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        var repository = new DynamoExecucaoRepository(dynamo, "execucao_service", new RastreamentoDeTeste().contexto());

        repository.agendarExecucao(comando(), Instant.parse("2026-11-02T14:00:00Z"));

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(captor.capture());
        assertThat(itemDaOutbox(captor.getValue())).doesNotContainKey("rastreamento");
    }

    @Test
    void aOutboxDevolveOContextoGravadoParaOPublicador() {
        Map<String, AttributeValue> item = new java.util.HashMap<>(Map.of("pk", s("OUTBOX#op-1"), "sk", s("RESULTADO"),
                "operationId", s("op-1"), "type", s("ExecucaoAgendada"), "sagaId", s("saga-1"), "osId", s("os-1"),
                "causationId", s("c"), "occurredAt", s("2026-11-02T14:00:00Z"), "orcamentoId", s("orc-1"),
                "pagamentoId", s("pag-1")));
        item.put("versaoOrcamento", AttributeValue.builder().n("1").build());
        item.put("rastreamento", AttributeValue.builder().m(Map.of("traceparent", s(TRACEPARENT))).build());
        when(dynamo.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(List.of(item)).build());

        OutboxEvent evento = new DynamoOutboxRepository(dynamo, "execucao_service").pendentes(10).get(0);

        assertThat(evento.rastreamento()).containsEntry("traceparent", TRACEPARENT);
    }

    @Test
    void eventoSemContextoTemMapaVazio() {
        assertThat(new OutboxEvent("op", "ExecucaoAgendada", "s", "o", "c", Instant.EPOCH, "orc", 1, "p",
                null, null, null, null).rastreamento()).isEmpty();
    }
}
