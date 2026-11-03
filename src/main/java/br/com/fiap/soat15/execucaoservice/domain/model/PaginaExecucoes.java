package br.com.fiap.soat15.execucaoservice.domain.model;

import java.util.List;

public record PaginaExecucoes(List<ExecucaoTrabalho> itens, String proximaPagina) {
    public PaginaExecucoes {
        itens = List.copyOf(itens);
    }
}
