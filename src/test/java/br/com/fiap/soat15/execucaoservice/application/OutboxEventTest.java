package br.com.fiap.soat15.execucaoservice.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {
    private final Instant agora = Instant.parse("2026-11-02T14:00:00Z");

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(OutboxEvent evento) {
        return (Map<String, Object>) evento.mensagem().get("payload");
    }

    private OutboxEvent evento(String tipo, String motivo, String itemId, Instant iniciadaEm) {
        return new OutboxEvent("op-1", tipo, "saga-1", "1", "causa-1", agora, "orc-1", 2, "pag-1", motivo, itemId, iniciadaEm);
    }

    @Test
    void montaOEnvelopeComIdentificadoresDaOperacao() {
        Map<String, Object> mensagem = evento("ExecucaoAgendada", null, null, null).mensagem();
        assertThat(mensagem).containsEntry("schemaVersion", 1).containsEntry("operationId", "op-1")
                .containsEntry("type", "ExecucaoAgendada").containsEntry("sagaId", "saga-1")
                .containsEntry("osId", "1").containsEntry("causationId", "causa-1")
                .containsEntry("occurredAt", "2026-11-02T14:00:00Z");
    }

    @Test
    void messageIdEDeterministicoPorTipoEOperacao() {
        Object primeiro = evento("ExecucaoAgendada", null, null, null).mensagem().get("messageId");
        assertThat(evento("ExecucaoAgendada", null, null, null).mensagem().get("messageId")).isEqualTo(primeiro);
        assertThat(evento("ExecucaoCancelada", null, null, null).mensagem().get("messageId")).isNotEqualTo(primeiro);
    }

    @Test
    void agendadaTrazStatusEIdentificadoresDoOrcamento() {
        assertThat(payload(evento("ExecucaoAgendada", null, null, null)))
                .containsEntry("status", "AGENDADA").containsEntry("orcamentoId", "orc-1")
                .containsEntry("versao", 2).containsEntry("pagamentoId", "pag-1");
    }

    @Test
    void rejeitadaTrazMotivoEItem() {
        assertThat(payload(evento("ExecucaoRejeitada", "ESTOQUE_INSUFICIENTE", "filtro", null)))
                .containsEntry("motivo", "ESTOQUE_INSUFICIENTE").containsEntry("itemId", "filtro")
                .doesNotContainKey("status");
    }

    @Test
    void canceladaERecusadaTrazemOQueMudou() {
        assertThat(payload(evento("ExecucaoCancelada", null, null, null))).containsEntry("status", "CANCELADA");
        assertThat(payload(evento("CancelamentoExecucaoRecusado", "ESTADO_NAO_PERMITE_CANCELAMENTO", null, null)))
                .containsEntry("motivo", "ESTADO_NAO_PERMITE_CANCELAMENTO").doesNotContainKey("status");
    }

    @Test
    void iniciadaEConcluidaTrazemOsHorarios() {
        Instant inicio = agora.minusSeconds(600);
        assertThat(payload(evento("ExecucaoIniciada", null, null, null)))
                .containsEntry("status", "EM_EXECUCAO").containsEntry("iniciadaEm", "2026-11-02T14:00:00Z");
        assertThat(payload(evento("ExecucaoConcluida", null, null, inicio)))
                .containsEntry("status", "CONCLUIDA").containsEntry("iniciadaEm", inicio.toString())
                .containsEntry("concluidaEm", "2026-11-02T14:00:00Z");
    }
}
