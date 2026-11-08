package br.com.fiap.soat15.execucaoservice.application;

import br.com.fiap.soat15.execucaoservice.domain.model.StatusExecucao;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoEventoExecucao;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OutboxEvent(String operationId, String type, String sagaId, String osId,
                          String causationId, Instant occurredAt, String orcamentoId,
                          int versaoOrcamento, String pagamentoId, String motivo, String itemId, Instant iniciadaEm,
                          Map<String, String> rastreamento) {
    public OutboxEvent {
        rastreamento = rastreamento == null ? Map.of() : Map.copyOf(rastreamento);
    }

    public OutboxEvent(String operationId, String type, String sagaId, String osId,
                       String causationId, Instant occurredAt, String orcamentoId,
                       int versaoOrcamento, String pagamentoId) {
        this(operationId, type, sagaId, osId, causationId, occurredAt, orcamentoId, versaoOrcamento, pagamentoId, null, null, null,
                Map.of());
    }

    public Map<String, Object> mensagem() {
        String messageId = UUID.nameUUIDFromBytes((type + ":" + operationId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return Map.of("schemaVersion", 1, "messageId", messageId,
                "operationId", operationId, "type", type, "sagaId", sagaId,
                "osId", osId, "causationId", causationId,
                "occurredAt", occurredAt.toString(), "payload", payload());
    }

    private Map<String, Object> payload() {
        if (TipoEventoExecucao.REJEITADA.codigo().equals(type)) {
            return Map.of("orcamentoId", orcamentoId, "versao", versaoOrcamento,
                    "pagamentoId", pagamentoId, "motivo", motivo, "itemId", itemId);
        }
        if (TipoEventoExecucao.CANCELADA.codigo().equals(type)) {
            return Map.of("status", StatusExecucao.CANCELADA.name(), "orcamentoId", orcamentoId, "versao", versaoOrcamento, "pagamentoId", pagamentoId);
        }
        if (TipoEventoExecucao.CANCELAMENTO_RECUSADO.codigo().equals(type)) {
            return Map.of("motivo", motivo, "orcamentoId", orcamentoId, "versao", versaoOrcamento, "pagamentoId", pagamentoId);
        }
        if (TipoEventoExecucao.CONCLUIDA.codigo().equals(type)) {
            return Map.of("orcamentoId", orcamentoId, "versao", versaoOrcamento, "pagamentoId", pagamentoId,
                    "status", StatusExecucao.CONCLUIDA.name(), "iniciadaEm", iniciadaEm.toString(), "concluidaEm", occurredAt.toString());
        }
        if (TipoEventoExecucao.INICIADA.codigo().equals(type)) {
            return Map.of("orcamentoId", orcamentoId, "versao", versaoOrcamento, "pagamentoId", pagamentoId,
                    "status", StatusExecucao.EM_EXECUCAO.name(), "iniciadaEm", occurredAt.toString());
        }
        return Map.of("orcamentoId", orcamentoId, "versao", versaoOrcamento, "pagamentoId", pagamentoId,
                "status", StatusExecucao.AGENDADA.name());
    }
}
