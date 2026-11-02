package br.com.fiap.soat15.execucaoservice.application;

public class ItemCatalogoExistenteException extends RuntimeException {
    public ItemCatalogoExistenteException(String itemId) {
        super("Item de catálogo já cadastrado: " + itemId);
    }
}
