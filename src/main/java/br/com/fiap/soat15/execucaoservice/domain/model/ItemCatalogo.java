package br.com.fiap.soat15.execucaoservice.domain.model;

import java.math.BigDecimal;

public record ItemCatalogo(
        TipoItem tipo,
        String itemId,
        String nome,
        BigDecimal precoUnitario,
        boolean ativo,
        Integer quantidadeEstoque
) {
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
        itemId = itemId.trim();
        nome = nome.trim();
    }
}
