package br.com.fiap.soat15.execucaoservice.domain.model;

import java.time.Instant;
import java.util.List;

public record Diagnostico(String osId, long versao, String status, List<ItemSolicitado> itens, Instant atualizadoEm) {
    public Diagnostico(String osId, long versao, List<ItemSolicitado> itens, Instant atualizadoEm) {
        this(osId, versao, "EM_DIAGNOSTICO", itens, atualizadoEm);
    }

    public Diagnostico {
        if (osId == null || osId.isBlank() || versao <= 0 || atualizadoEm == null
                || !"EM_DIAGNOSTICO".equals(status)) {
            throw new IllegalArgumentException("Diagnóstico inválido");
        }
        itens = List.copyOf(itens);
    }
}
