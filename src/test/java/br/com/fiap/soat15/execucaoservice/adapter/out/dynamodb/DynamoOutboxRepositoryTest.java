package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoOutboxRepositoryTest {
    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final DynamoOutboxRepository repository = new DynamoOutboxRepository(dynamo, "execucao_service");
    private final Instant agora = Instant.parse("2026-11-02T14:00:00Z");
    private final OutboxEvent evento = new OutboxEvent("op-123", "ExecucaoAgendada", "saga-123", "123",
            "causa-123", agora, "orc-123", 2, "pag-123");

    @Test
    void continuaPaginacaoQuandoFiltroNaoEncontraEventoNaPrimeiraPagina() {
        Map<String, AttributeValue> cursor = Map.of("pk", texto("CATALOGO#1"), "sk", texto("CADASTRO"));
        when(dynamo.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().items(List.of()).lastEvaluatedKey(cursor).build())
                .thenReturn(ScanResponse.builder().items(List.of(item())).build());

        assertThat(repository.pendentes(1)).containsExactly(evento);

        ArgumentCaptor<ScanRequest> captor = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamo, times(2)).scan(captor.capture());
        assertThat(captor.getAllValues().get(1).exclusiveStartKey()).isEqualTo(cursor);
    }

    @Test
    void reservaComPrazoEConfirmaApenasParaODonoDaReserva() {
        Instant ate = agora.plusSeconds(120);

        assertThat(repository.reservar(evento, "worker-1", agora, ate)).isTrue();
        repository.marcarPublicado(evento, "worker-1", agora);

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo, times(2)).updateItem(captor.capture());
        UpdateItemRequest reserva = captor.getAllValues().get(0);
        UpdateItemRequest confirmacao = captor.getAllValues().get(1);
        assertThat(reserva.key().get("pk").s()).isEqualTo("OUTBOX#op-123");
        assertThat(reserva.conditionExpression()).contains("leaseUntil < :agora");
        assertThat(reserva.expressionAttributeValues().get(":ate").n())
                .isEqualTo(Long.toString(ate.toEpochMilli()));
        assertThat(confirmacao.conditionExpression()).contains("leaseOwner = :dono");
        assertThat(confirmacao.expressionAttributeValues().get(":true").bool()).isTrue();
    }

    private Map<String, AttributeValue> item() {
        return Map.of("operationId", texto("op-123"), "type", texto("ExecucaoAgendada"),
                "sagaId", texto("saga-123"), "osId", texto("123"),
                "causationId", texto("causa-123"), "occurredAt", texto(agora.toString()),
                "orcamentoId", texto("orc-123"), "versaoOrcamento", AttributeValue.builder().n("2").build(),
                "pagamentoId", texto("pag-123"));
    }

    private AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }
}
