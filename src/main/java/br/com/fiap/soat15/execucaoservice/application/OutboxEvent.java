package br.com.fiap.soat15.execucaoservice.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OutboxEvent(String operationId, String type, String sagaId, String osId,
                          String causationId, Instant occurredAt, String orcamentoId,
                          int versaoOrcamento, String pagamentoId) {
    public Map<String, Object> mensagem() {
        String messageId = UUID.nameUUIDFromBytes((type + ":" + operationId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return Map.of("schemaVersion", 1, "messageId", messageId,
                "operationId", operationId, "type", type, "sagaId", sagaId,
                "osId", osId, "causationId", causationId,
                "occurredAt", occurredAt.toString(), "payload", Map.of(
                        "orcamentoId", orcamentoId, "versao", versaoOrcamento,
                        "pagamentoId", pagamentoId));
    }
}
