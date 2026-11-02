package br.com.fiap.soat15.execucaoservice.application;

import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;

public class ItemCatalogoNaoEncontradoException extends RuntimeException {
    public ItemCatalogoNaoEncontradoException(TipoItem tipo, String itemId) {
        super("Item de catálogo não encontrado: " + tipo + "/" + itemId);
    }
}
