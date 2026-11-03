package br.com.fiap.soat15.execucaoservice.adapter.out.sqs;

import br.com.fiap.soat15.execucaoservice.application.ExecucaoMetricas;
import br.com.fiap.soat15.execucaoservice.application.OutboxEvent;
import br.com.fiap.soat15.execucaoservice.application.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {
    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final SqsClient sqs = mock(SqsClient.class);
    private final Instant agora = Instant.parse("2026-11-02T14:00:00Z");
    private final OutboxEvent evento = new OutboxEvent("op-123", "ExecucaoAgendada", "saga-123", "123",
            "causa-123", agora, "orc-123", 2, "pag-123");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxPublisher publisher = new OutboxPublisher(repository, new ExecucaoMetricas(registry), sqs,
            Clock.fixed(agora, ZoneOffset.UTC), "http://localhost:9324/queue/execucao-resultados");

    @Test
    void enviaAntesDeMarcarComoPublicado() {
        when(repository.pendentes(20)).thenReturn(List.of(evento));
        when(repository.reservar(eq(evento), any(), eq(agora), any())).thenReturn(true);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("sqs-1").build());

        publisher.publicarPendentes();

        InOrder ordem = inOrder(repository, sqs);
        ordem.verify(repository).reservar(eq(evento), any(), eq(agora), any());
        ordem.verify(sqs).sendMessage(any(SendMessageRequest.class));
        ordem.verify(repository).marcarPublicado(eq(evento), any(), eq(agora));
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        assertThat(captor.getValue().messageBody()).contains("\"type\":\"ExecucaoAgendada\"")
                .contains("\"operationId\":\"op-123\"")
                .contains("\"orcamentoId\":\"orc-123\"");
    }

    @Test
    void falhaDoSqsMantemEventoPendenteParaNovaTentativa() {
        when(repository.pendentes(20)).thenReturn(List.of(evento));
        when(repository.reservar(eq(evento), any(), eq(agora), any())).thenReturn(true);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new IllegalStateException("SQS indisponível"));

        publisher.publicarPendentes();
        verify(repository, never()).marcarPublicado(eq(evento), any(), any());
        assertThat(registry.counter("oficina.execucao.eventos.falhas").count()).isEqualTo(1);
        assertThat(registry.find("oficina.execucao.eventos.publicados").counter()).isNull();
    }

    @Test
    void contaOEventoPublicadoPorTipo() {
        when(repository.pendentes(20)).thenReturn(List.of(evento));
        when(repository.reservar(eq(evento), any(), eq(agora), any())).thenReturn(true);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("sqs-1").build());

        publisher.publicarPendentes();

        assertThat(registry.counter("oficina.execucao.eventos.publicados", "tipo", "ExecucaoAgendada").count()).isEqualTo(1);
        assertThat(registry.counter("oficina.execucao.eventos.falhas").count()).isZero();
    }

    @Test
    void eventoReservadoPorOutraReplicaNaoEContado() {
        when(repository.pendentes(20)).thenReturn(List.of(evento));
        when(repository.reservar(eq(evento), any(), eq(agora), any())).thenReturn(false);

        publisher.publicarPendentes();

        assertThat(registry.find("oficina.execucao.eventos.publicados").counter()).isNull();
    }

    @Test
    void medeOAtrasoDoEventoPendenteMaisAntigo() {
        OutboxEvent antigo = new OutboxEvent("op-1", "ExecucaoAgendada", "saga-1", "1", "c", agora.minusSeconds(90), "o", 1, "p");
        OutboxEvent recente = new OutboxEvent("op-2", "ExecucaoAgendada", "saga-2", "2", "c", agora.minusSeconds(10), "o", 1, "p");
        when(repository.pendentes(20)).thenReturn(List.of(recente, antigo));
        when(repository.reservar(any(), any(), any(), any())).thenReturn(false);

        publisher.publicarPendentes();
        assertThat(registry.get("oficina.execucao.outbox.atraso").gauge().value()).isEqualTo(90);

        when(repository.pendentes(20)).thenReturn(List.of());
        publisher.publicarPendentes();
        assertThat(registry.get("oficina.execucao.outbox.atraso").gauge().value()).isZero();
    }
}
