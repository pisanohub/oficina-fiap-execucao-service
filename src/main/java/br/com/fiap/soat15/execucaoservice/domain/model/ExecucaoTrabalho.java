package br.com.fiap.soat15.execucaoservice.domain.model;

import java.time.Instant;
import java.util.List;

public record ExecucaoTrabalho(String osId, String status, String sagaId, List<ItemSolicitado> itens,
                               Instant agendadaEm, Instant iniciadaEm, Instant concluidaEm) {}
