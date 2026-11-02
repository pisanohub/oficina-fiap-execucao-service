package br.com.fiap.soat15.execucaoservice.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OutboxEvent(String operationId, String type, String sagaId, String osId,
                          String causationId, Instant occurredAt, String orcamentoId,
                          int versaoOrcamento, String pagamentoId, String motivo, String itemId, Instant iniciadaEm) {
    public OutboxEvent(String operationId, String type, String sagaId, String osId,
                       String causationId, Instant occurredAt, String orcamentoId,
                       int versaoOrcamento, String pagamentoId) {
        this(operationId, type, sagaId, osId, causationId, occurredAt, orcamentoId, versaoOrcamento, pagamentoId, null, null, null);
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
        if ("ExecucaoRejeitada".equals(type)) {
            return Map.of("orcamentoId", orcamentoId, "versao", versaoOrcamento,
                    "pagamentoId", pagamentoId, "motivo", motivo, "itemId", itemId);
        }
        if ("ExecucaoCancelada".equals(type)) {
            return Map.of("status", "CANCELADA", "orcamentoId", orcamentoId, "versao", versaoOrcamento, "pagamentoId", pagamentoId);
        }
        if ("CancelamentoExecucaoRecusado".equals(type)) {
            return Map.of("motivo", motivo, "orcamentoId", orcamentoId, "versao", versaoOrcamento, "pagamentoId", pagamentoId);
        }
        if ("ExecucaoConcluida".equals(type)) {
            return Map.of("orcamentoId", orcamentoId, "versao", versaoOrcamento, "pagamentoId", pagamentoId,
                    "status", "CONCLUIDA", "iniciadaEm", iniciadaEm.toString(), "concluidaEm", occurredAt.toString());
        }
        if ("ExecucaoIniciada".equals(type)) {
            return Map.of("orcamentoId", orcamentoId, "versao", versaoOrcamento, "pagamentoId", pagamentoId,
                    "status", "EM_EXECUCAO", "iniciadaEm", occurredAt.toString());
        }
        return Map.of("orcamentoId", orcamentoId, "versao", versaoOrcamento, "pagamentoId", pagamentoId,
                "status", "AGENDADA");
    }
}
