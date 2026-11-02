package br.com.fiap.soat15.execucaoservice.domain.model;

public record ItemSolicitado(TipoItem tipo, String itemId, int quantidade) {
    public ItemSolicitado {
        if (tipo == null || itemId == null || itemId.isBlank() || quantidade <= 0) {
            throw new IllegalArgumentException("Item precisa de tipo, itemId e quantidade positiva");
        }
        itemId = itemId.trim();
    }
}
