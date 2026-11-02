package br.com.fiap.soat15.execucaoservice.domain.model;

import java.math.BigDecimal;

public record ItemCotado(TipoItem tipo, String itemId, String nome, int quantidade, BigDecimal precoUnitario) {
}
