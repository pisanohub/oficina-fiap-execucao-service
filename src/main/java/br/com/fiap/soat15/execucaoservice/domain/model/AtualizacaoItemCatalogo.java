package br.com.fiap.soat15.execucaoservice.domain.model;

import java.math.BigDecimal;

public record AtualizacaoItemCatalogo(String nome, String descricao, BigDecimal precoUnitario, boolean ativo,
                                      Integer tempoEstimadoMinutos) {
    public AtualizacaoItemCatalogo {
        if (nome == null || nome.isBlank() || precoUnitario == null
                || precoUnitario.signum() < 0 || precoUnitario.scale() > 2) {
            throw new IllegalArgumentException("Atualização de item de catálogo inválida");
        }
        nome = nome.trim();
        descricao = ItemCatalogo.descricaoValida(descricao);
        if (tempoEstimadoMinutos != null && tempoEstimadoMinutos < 1) {
            throw new IllegalArgumentException("Tempo estimado deve ser maior que zero");
        }
    }
}
