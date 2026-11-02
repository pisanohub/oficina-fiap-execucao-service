package br.com.fiap.soat15.execucaoservice.contract;

import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contrato provisório v1; o consumo SQS será habilitado após publicação da outbox. */
public record AgendarExecucaoCommand(
        int schemaVersion,
        String messageId,
        String operationId,
        String type,
        String sagaId,
        String osId,
        String causationId,
        String occurredAt,
        Payload payload
) {
    public AgendarExecucaoCommand {
        if (schemaVersion != 1 || !"AgendarExecucao".equals(type) || vazio(messageId)
                || vazio(occurredAt)
                || vazio(operationId) || vazio(sagaId) || vazio(osId) || payload == null) {
            throw new IllegalArgumentException("Envelope AgendarExecucao v1 inválido");
        }
        UUID.fromString(messageId);
        Instant.parse(occurredAt);
    }

    public record Payload(String orcamentoId, int versao, String pagamentoId, List<ItemSolicitado> itens) {
        public Payload {
            if (vazio(orcamentoId) || versao <= 0 || vazio(pagamentoId)
                    || itens == null || itens.isEmpty() || itens.size() > 20) {
                throw new IllegalArgumentException("Payload de AgendarExecucao inválido");
            }
            itens = List.copyOf(itens);
        }
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }
}
