package br.com.fiap.soat15.execucaoservice.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemCatalogoTest {
    private static final BigDecimal PRECO = new BigDecimal("10.00");

    @Test
    void normalizaIdNomeEDescricao() {
        ItemCatalogo item = new ItemCatalogo(TipoItem.PECA, " filtro ", " Filtro ", " original ", PRECO, true, 3, null);
        assertThat(item.itemId()).isEqualTo("filtro");
        assertThat(item.nome()).isEqualTo("Filtro");
        assertThat(item.descricao()).isEqualTo("original");
    }

    @Test
    void aceitaServicoSemEstoqueComTempoEstimado() {
        ItemCatalogo servico = new ItemCatalogo(TipoItem.SERVICO, "troca", "Troca", null, PRECO, true, null, 45);
        assertThat(servico.tempoEstimadoMinutos()).isEqualTo(45);
        assertThat(servico.quantidadeEstoque()).isNull();
    }

    @Test
    void rejeitaDadosBasicosInvalidos() {
        assertThatThrownBy(() -> new ItemCatalogo(null, "a", "A", PRECO, true, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, null, "A", PRECO, true, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, " ", "A", PRECO, true, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, "a", null, PRECO, true, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, "a", " ", PRECO, true, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejeitaPrecoInvalido() {
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, "a", "A", null, true, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, "a", "A", new BigDecimal("-0.01"), true, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, "a", "A", new BigDecimal("1.234"), true, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ItemCatalogo(TipoItem.PECA, "a", "A", BigDecimal.ZERO, true, 0).precoUnitario()).isEqualByComparingTo("0");
    }

    @Test
    void pecaExigeEstoqueNaoNegativoEServicoNaoTemEstoque() {
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, "a", "A", PRECO, true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, "a", "A", PRECO, true, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.SERVICO, "s", "S", PRECO, true, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validaAAtualizacaoDeItem() {
        assertThat(new AtualizacaoItemCatalogo(" Novo ", " d ", PRECO, false, 10).nome()).isEqualTo("Novo");
        assertThatThrownBy(() -> new AtualizacaoItemCatalogo(null, null, PRECO, true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AtualizacaoItemCatalogo("N", null, null, true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AtualizacaoItemCatalogo("N", null, new BigDecimal("-1"), true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AtualizacaoItemCatalogo("N", null, new BigDecimal("1.234"), true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AtualizacaoItemCatalogo("N", null, PRECO, true, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validaItemSolicitado() {
        assertThat(new ItemSolicitado(TipoItem.PECA, " filtro ", 2).itemId()).isEqualTo("filtro");
        assertThatThrownBy(() -> new ItemSolicitado(null, "a", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemSolicitado(TipoItem.PECA, " ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemSolicitado(TipoItem.PECA, "a", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
