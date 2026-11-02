package br.com.fiap.soat15.execucaoservice.application;

public class PecaIndisponivelException extends RuntimeException {
    public PecaIndisponivelException(String itemId, Throwable causa) {
        super("Peça ausente, inativa ou sem estoque suficiente: " + itemId, causa);
    }
}
