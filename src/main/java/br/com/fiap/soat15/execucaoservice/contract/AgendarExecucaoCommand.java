package br.com.fiap.soat15.execucaoservice.contract;

import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;

import java.util.List;

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
        if (schemaVersion != 1 || !TipoComandoExecucao.AGENDAR.codigo().equals(type) || vazio(messageId)
                || vazio(occurredAt)
                || vazio(operationId) || vazio(sagaId) || vazio(osId) || payload == null
                || !EnvelopeValidacao.uuidValido(messageId) || !EnvelopeValidacao.instanteValido(occurredAt)) {
            throw new IllegalArgumentException("Envelope AgendarExecucao v1 inválido");
        }
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
