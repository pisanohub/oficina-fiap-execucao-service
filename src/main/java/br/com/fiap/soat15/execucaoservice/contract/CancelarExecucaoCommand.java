package br.com.fiap.soat15.execucaoservice.contract;

import java.time.Instant;
import java.util.UUID;

public record CancelarExecucaoCommand(int schemaVersion, String messageId, String operationId,
        String type, String sagaId, String osId, String causationId, String occurredAt) {
    public CancelarExecucaoCommand {
        if (schemaVersion != 1 || !TipoComandoExecucao.CANCELAR.codigo().equals(type) || vazio(messageId)
                || vazio(operationId) || vazio(sagaId) || vazio(osId) || vazio(occurredAt)) {
            throw new IllegalArgumentException("Envelope CancelarExecucao v1 inválido");
        }
        UUID.fromString(messageId);
        Instant.parse(occurredAt);
    }
    private static boolean vazio(String valor) { return valor == null || valor.isBlank(); }
}
