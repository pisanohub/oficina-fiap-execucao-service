package br.com.fiap.soat15.execucaoservice.domain.model;

import java.math.BigDecimal;

public record ItemCatalogo(
        TipoItem tipo,
        String itemId,
        String nome,
        String descricao,
        BigDecimal precoUnitario,
        boolean ativo,
        Integer quantidadeEstoque,
        Integer tempoEstimadoMinutos
) {
    public static final int TAMANHO_MAXIMO_DESCRICAO = 300;

    public ItemCatalogo {
        if (tipo == null || itemId == null || itemId.isBlank() || nome == null || nome.isBlank()
                || precoUnitario == null || precoUnitario.signum() < 0 || precoUnitario.scale() > 2) {
            throw new IllegalArgumentException("Item de catálogo inválido");
        }
        if (tipo == TipoItem.PECA && (quantidadeEstoque == null || quantidadeEstoque < 0)) {
            throw new IllegalArgumentException("Peça precisa de estoque não negativo");
        }
        if (tipo == TipoItem.SERVICO && quantidadeEstoque != null) {
            throw new IllegalArgumentException("Serviço não possui estoque");
        }
        descricao = descricaoValida(descricao);
        tempoEstimadoValido(tipo, tempoEstimadoMinutos);
        itemId = itemId.trim();
        nome = nome.trim();
    }

    public ItemCatalogo(TipoItem tipo, String itemId, String nome, BigDecimal precoUnitario,
                        boolean ativo, Integer quantidadeEstoque) {
        this(tipo, itemId, nome, null, precoUnitario, ativo, quantidadeEstoque, null);
    }

    static String descricaoValida(String descricao) {
        if (descricao == null || descricao.isBlank()) {
            return null;
        }
        if (descricao.trim().length() > TAMANHO_MAXIMO_DESCRICAO) {
            throw new IllegalArgumentException("Descrição deve ter no máximo " + TAMANHO_MAXIMO_DESCRICAO + " caracteres");
        }
        return descricao.trim();
    }

    static void tempoEstimadoValido(TipoItem tipo, Integer tempoEstimadoMinutos) {
        if (tempoEstimadoMinutos == null) {
            return;
        }
        if (tipo == TipoItem.PECA) {
            throw new IllegalArgumentException("Peça não possui tempo estimado");
        }
        if (tempoEstimadoMinutos < 1) {
            throw new IllegalArgumentException("Tempo estimado deve ser maior que zero");
        }
    }
}
