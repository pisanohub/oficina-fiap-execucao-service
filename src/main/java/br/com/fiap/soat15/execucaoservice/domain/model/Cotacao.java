package br.com.fiap.soat15.execucaoservice.domain.model;

import java.time.Instant;
import java.util.List;

public record Cotacao(String moeda, Instant geradaEm, List<ItemCotado> itens) {
    public Cotacao {
        itens = List.copyOf(itens);
    }
}
