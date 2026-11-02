package br.com.fiap.soat15.execucaoservice.domain.model;

import java.time.Instant;

public record AgendamentoExecucao(String osId, String operationId, String sagaId,
                                  String orcamentoId, int versaoOrcamento, String pagamentoId,
                                  Instant agendadoEm) {
}
