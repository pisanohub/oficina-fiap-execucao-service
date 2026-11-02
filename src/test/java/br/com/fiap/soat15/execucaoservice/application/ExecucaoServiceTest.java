package br.com.fiap.soat15.execucaoservice.application;

import br.com.fiap.soat15.execucaoservice.domain.model.ExecucaoTrabalho;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.contract.CancelarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.domain.model.AgendamentoExecucao;
import br.com.fiap.soat15.execucaoservice.domain.model.AtualizacaoItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecucaoServiceTest {
    private RepositorioEmMemoria repository;
    private ExecucaoService service;
    private final Instant agora = Instant.parse("2026-11-02T14:00:00Z");

    @BeforeEach
    void preparar() {
        repository = new RepositorioEmMemoria();
        service = new ExecucaoService(repository, Clock.fixed(agora, ZoneOffset.UTC));
    }

    @Test
    void iniciaDiagnosticoSemDispararOrcamento() {
        Diagnostico inicial = service.iniciarDiagnostico(" 123 ");
        assertThat(inicial.osId()).isEqualTo("123");
        assertThat(inicial.versao()).isEqualTo(1);
        assertThat(inicial.status()).isEqualTo("EM_DIAGNOSTICO");
        assertThat(inicial.itens()).isEmpty();
        assertThat(service.iniciarDiagnostico("123")).isEqualTo(inicial);
    }

    @Test
    void rejeitaItensRepetidosAntesDeAlterarDiagnostico() {
        service.iniciarDiagnostico("123");
        ItemSolicitado peca = new ItemSolicitado(TipoItem.PECA, "filtro", 1);
        assertThatThrownBy(() -> service.atualizarItens("123", 1, List.of(peca, peca)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repetidos");
        assertThat(service.consultarDiagnostico("123").versao()).isEqualTo(1);
    }

    @Test
    void diagnosticoNaoAceitaItemInativo() {
        service.iniciarDiagnostico("123");
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro",
                new BigDecimal("25.00"), false, 3));
        assertThatThrownBy(() -> service.atualizarItens("123", 1,
                List.of(new ItemSolicitado(TipoItem.PECA, "filtro", 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ausente ou inativo");
        assertThat(service.consultarDiagnostico("123").itens()).isEmpty();
    }

    @Test
    void cotacaoDevolvePrecoDoCatalogoEQuantidadePedida() {
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro de óleo",
                new BigDecimal("25.00"), true, 10));
        var cotacao = service.cotar(List.of(new ItemSolicitado(TipoItem.PECA, "filtro", 2)));
        assertThat(cotacao.moeda()).isEqualTo("BRL");
        assertThat(cotacao.geradaEm()).isEqualTo(agora);
        assertThat(cotacao.itens()).singleElement().satisfies(item -> {
            assertThat(item.quantidade()).isEqualTo(2);
            assertThat(item.precoUnitario()).isEqualByComparingTo("25.00");
        });
    }

    @Test
    void diagnosticoComItensEVersaoPodeSerLidoPeloOs() {
        service.iniciarDiagnostico("123");
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.SERVICO, "troca-oleo", "Troca de óleo",
                new BigDecimal("90.00"), true, null));
        List<ItemSolicitado> itens = List.of(new ItemSolicitado(TipoItem.SERVICO, "troca-oleo", 1));
        Diagnostico atualizado = service.atualizarItens("123", 1, itens);
        assertThat(atualizado.versao()).isEqualTo(2);
        assertThat(service.consultarDiagnostico("123").itens()).containsExactlyElementsOf(itens);
        assertThatThrownBy(() -> service.atualizarItens("123", 1, itens))
                .isInstanceOf(VersaoDiagnosticoConflitanteException.class);
    }

    @Test
    void quantidadeOuPrecoInvalidosNaoChegamAoBanco() {
        assertThatThrownBy(() -> new ItemSolicitado(TipoItem.SERVICO, "troca-oleo", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro",
                new BigDecimal("5.001"), true, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.catalogo).isEmpty();
    }

    @Test
    void agendamentoRejeitaItensRepetidosAntesDeReservarEstoque() {
        ItemSolicitado peca = new ItemSolicitado(TipoItem.PECA, "filtro", 1);
        AgendarExecucaoCommand comando = comandoAgendar(List.of(peca, peca));

        assertThatThrownBy(() -> service.agendarExecucao(comando))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repetidos");
        assertThat(repository.comandoAgendado).isNull();
    }

    @Test
    void agendamentoEncaminhaComandoValidadoAoRepositorio() {
        AgendarExecucaoCommand comando = comandoAgendar(List.of(
                new ItemSolicitado(TipoItem.PECA, "filtro", 1)));

        AgendamentoExecucao agendamento = service.agendarExecucao(comando);

        assertThat(repository.comandoAgendado).isSameAs(comando);
        assertThat(repository.instanteAgendamento).isEqualTo(agora);
        assertThat(agendamento.osId()).isEqualTo("123");
    }

    private AgendarExecucaoCommand comandoAgendar(List<ItemSolicitado> itens) {
        return new AgendarExecucaoCommand(1, "02539ee2-c754-4bb7-b178-4b328de9dbd5",
                "agendar-os-123-v2", "AgendarExecucao", "saga-123", "123", "pagamento-confirmado-123",
                agora.toString(), new AgendarExecucaoCommand.Payload("orc-123", 2, "pag-123", itens));
    }

    @Test
    void listaCatalogoFiltrandoPorTipoEAtivo() {
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro", new BigDecimal("25.00"), true, 3));
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "velha", "Velha", new BigDecimal("5.00"), false, 1));
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.SERVICO, "troca", "Troca", new BigDecimal("90.00"), true, null));
        assertThat(service.listarCatalogo(null, null, null)).hasSize(3);
        assertThat(service.listarCatalogo(TipoItem.PECA, null, null)).extracting(ItemCatalogo::itemId)
                .containsExactlyInAnyOrder("filtro", "velha");
        assertThat(service.listarCatalogo(TipoItem.PECA, true, null)).extracting(ItemCatalogo::itemId).containsExactly("filtro");
        assertThat(service.listarCatalogo(null, false, null)).extracting(ItemCatalogo::itemId).containsExactly("velha");
    }

    @Test
    void consultaItemDoCatalogoOuFalhaQuandoNaoExiste() {
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro", new BigDecimal("25.00"), true, 3));
        assertThat(service.consultarItemCatalogo(TipoItem.PECA, " filtro ").nome()).isEqualTo("Filtro");
        assertThatThrownBy(() -> service.consultarItemCatalogo(TipoItem.PECA, "nada"))
                .isInstanceOf(ItemCatalogoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.consultarItemCatalogo(TipoItem.PECA, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atualizaDadosDoItemSemAlterarEstoque() {
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro", new BigDecimal("25.00"), true, 3));
        ItemCatalogo atualizado = service.atualizarItemCatalogo(TipoItem.PECA, "filtro", " Filtro novo ",
                null, new BigDecimal("30.50"), false, null);
        assertThat(atualizado.nome()).isEqualTo("Filtro novo");
        assertThat(atualizado.precoUnitario()).isEqualByComparingTo("30.50");
        assertThat(atualizado.ativo()).isFalse();
        assertThat(atualizado.quantidadeEstoque()).isEqualTo(3);
    }

    @Test
    void rejeitaAtualizacaoInvalidaDoItem() {
        assertThatThrownBy(() -> service.atualizarItemCatalogo(TipoItem.PECA, "filtro", "Filtro", null, new BigDecimal("1.00"), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ativo");
        assertThatThrownBy(() -> service.atualizarItemCatalogo(TipoItem.PECA, "filtro", "Filtro", null, new BigDecimal("1.234"), true, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.atualizarItemCatalogo(TipoItem.PECA, "filtro", " ", null, new BigDecimal("1.00"), true, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.atualizarItemCatalogo(TipoItem.PECA, "inexistente", "Filtro", null, new BigDecimal("1.00"), true, null))
                .isInstanceOf(ItemCatalogoNaoEncontradoException.class);
    }

    @Test
    void reporEstoqueSomaAQuantidadeInformada() {
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro", new BigDecimal("25.00"), true, 3));
        assertThat(service.reporEstoque(TipoItem.PECA, "filtro", 7).quantidadeEstoque()).isEqualTo(10);
    }

    @Test
    void rejeitaQuantidadeInvalidaNaReposicao() {
        assertThatThrownBy(() -> service.reporEstoque(TipoItem.PECA, "filtro", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reporEstoque(TipoItem.PECA, "filtro", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reporEstoque(TipoItem.PECA, "filtro", 1_000_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reporEstoque(TipoItem.PECA, "inexistente", 1))
                .isInstanceOf(ItemCatalogoNaoEncontradoException.class);
    }

    @Test
    void ajustaEstoqueParaUmValorAbsoluto() {
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro", new BigDecimal("25.00"), true, 3));
        assertThat(service.ajustarEstoque(TipoItem.PECA, "filtro", 0).quantidadeEstoque()).isZero();
        assertThat(service.ajustarEstoque(TipoItem.PECA, "filtro", 42).quantidadeEstoque()).isEqualTo(42);
        assertThatThrownBy(() -> service.ajustarEstoque(TipoItem.PECA, "filtro", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ajustarEstoque(TipoItem.PECA, "filtro", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void servicoNaoTemEstoqueParaRepor() {
        assertThatThrownBy(() -> service.reporEstoque(TipoItem.SERVICO, "troca", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("não possui estoque");
        assertThatThrownBy(() -> service.ajustarEstoque(TipoItem.SERVICO, "troca", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("não possui estoque");
    }

    @Test
    void listaCatalogoPorNomeIgnorandoAcentosEMaiusculas() {
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.SERVICO, "troca", "Troca de óleo", new BigDecimal("90.00"), true, null));
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro de óleo", new BigDecimal("25.00"), true, 3));
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "pastilha", "Pastilha de freio", new BigDecimal("80.00"), true, 3));
        assertThat(service.listarCatalogo(null, null, "OLEO")).extracting(ItemCatalogo::itemId)
                .containsExactlyInAnyOrder("troca", "filtro");
        assertThat(service.listarCatalogo(TipoItem.PECA, null, "  freio ")).extracting(ItemCatalogo::itemId)
                .containsExactly("pastilha");
        assertThat(service.listarCatalogo(null, null, "inexistente")).isEmpty();
        assertThat(service.listarCatalogo(null, null, " ")).hasSize(3);
    }

    @Test
    void atualizaDescricaoETempoEstimadoDoServico() {
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.SERVICO, "troca", "Troca", new BigDecimal("90.00"), true, null));
        ItemCatalogo atualizado = service.atualizarItemCatalogo(TipoItem.SERVICO, "troca", "Troca de óleo",
                " Inclui filtro ", new BigDecimal("95.00"), true, 45);
        assertThat(atualizado.descricao()).isEqualTo("Inclui filtro");
        assertThat(atualizado.tempoEstimadoMinutos()).isEqualTo(45);
    }

    @Test
    void pecaNaoAceitaTempoEstimadoETempoPrecisaSerPositivo() {
        assertThatThrownBy(() -> service.atualizarItemCatalogo(TipoItem.PECA, "filtro", "Filtro", null,
                new BigDecimal("1.00"), true, 10)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempo estimado");
        assertThatThrownBy(() -> service.atualizarItemCatalogo(TipoItem.SERVICO, "troca", "Troca", null,
                new BigDecimal("1.00"), true, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.PECA, "f", "F", null, new BigDecimal("1.00"), true, 1, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.SERVICO, "s", "S", null, new BigDecimal("1.00"), true, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void descricaoLongaEhRejeitadaEDescricaoEmBrancoViraNula() {
        String longa = "x".repeat(301);
        assertThatThrownBy(() -> new ItemCatalogo(TipoItem.SERVICO, "s", "S", longa, new BigDecimal("1.00"), true, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("300");
        assertThat(new ItemCatalogo(TipoItem.SERVICO, "s", "S", "   ", new BigDecimal("1.00"), true, null, null).descricao()).isNull();
    }

    @Test
    void inativaItemDoCatalogoSemApagarOCadastro() {
        service.salvarItemCatalogo(new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro", new BigDecimal("25.00"), true, 3));
        service.inativarItemCatalogo(TipoItem.PECA, " filtro ");
        ItemCatalogo item = service.consultarItemCatalogo(TipoItem.PECA, "filtro");
        assertThat(item.ativo()).isFalse();
        assertThat(item.quantidadeEstoque()).isEqualTo(3);
        assertThatThrownBy(() -> service.inativarItemCatalogo(TipoItem.PECA, "nada"))
                .isInstanceOf(ItemCatalogoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.inativarItemCatalogo(TipoItem.PECA, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static class RepositorioEmMemoria implements ExecucaoRepository {
        public void cancelarExecucao(CancelarExecucaoCommand comando, Instant agora) { throw new UnsupportedOperationException(); }
        public ExecucaoTrabalho concluirExecucao(String osId, Instant agora) { throw new UnsupportedOperationException(); }
        public List<ExecucaoTrabalho> listarExecucoes() { throw new UnsupportedOperationException(); }
        public Optional<ExecucaoTrabalho> buscarExecucao(String osId) { throw new UnsupportedOperationException(); }
        public ExecucaoTrabalho iniciarExecucao(String osId, Instant agora) { throw new UnsupportedOperationException(); }
        private final Map<String, Diagnostico> diagnosticos = new HashMap<>();
        private final Map<String, ItemCatalogo> catalogo = new HashMap<>();
        private AgendarExecucaoCommand comandoAgendado;
        private Instant instanteAgendamento;

        @Override
        public AgendamentoExecucao agendarExecucao(AgendarExecucaoCommand comando, Instant agora) {
            comandoAgendado = comando;
            instanteAgendamento = agora;
            return new AgendamentoExecucao(comando.osId(), comando.operationId(), comando.sagaId(),
                    comando.payload().orcamentoId(), comando.payload().versao(),
                    comando.payload().pagamentoId(), agora);
        }

        @Override
        public Diagnostico iniciarDiagnostico(String osId, Instant agora) {
            return diagnosticos.computeIfAbsent(osId, id -> new Diagnostico(id, 1, List.of(), agora));
        }

        @Override
        public Diagnostico atualizarItens(String osId, long versaoEsperada, List<ItemSolicitado> itens, Instant agora) {
            Diagnostico anterior = diagnosticos.get(osId);
            if (anterior == null) throw new DiagnosticoNaoEncontradoException(osId);
            if (anterior.versao() != versaoEsperada) throw new VersaoDiagnosticoConflitanteException(osId);
            Diagnostico atualizado = new Diagnostico(osId, versaoEsperada + 1, itens, agora);
            diagnosticos.put(osId, atualizado);
            return atualizado;
        }

        @Override
        public Optional<Diagnostico> buscarDiagnostico(String osId) {
            return Optional.ofNullable(diagnosticos.get(osId));
        }

        @Override
        public void salvarItemCatalogo(ItemCatalogo item) {
            catalogo.put(item.tipo() + "/" + item.itemId(), item);
        }

        @Override
        public Optional<ItemCatalogo> buscarItemCatalogo(TipoItem tipo, String itemId) {
            return Optional.ofNullable(catalogo.get(tipo + "/" + itemId));
        }

        @Override
        public List<ItemCatalogo> listarItensCatalogo(TipoItem tipo, Boolean ativo) {
            return catalogo.values().stream()
                    .filter(item -> tipo == null || item.tipo() == tipo)
                    .filter(item -> ativo == null || item.ativo() == ativo).toList();
        }

        @Override
        public ItemCatalogo atualizarItemCatalogo(TipoItem tipo, String itemId, AtualizacaoItemCatalogo atualizacao) {
            ItemCatalogo atual = buscarItemCatalogo(tipo, itemId)
                    .orElseThrow(() -> new ItemCatalogoNaoEncontradoException(tipo, itemId));
            ItemCatalogo novo = new ItemCatalogo(tipo, itemId, atualizacao.nome(), atualizacao.descricao(),
                    atualizacao.precoUnitario(), atualizacao.ativo(), atual.quantidadeEstoque(),
                    atualizacao.tempoEstimadoMinutos());
            catalogo.put(tipo + "/" + itemId, novo);
            return novo;
        }

        @Override
        public void inativarItemCatalogo(TipoItem tipo, String itemId) {
            ItemCatalogo atual = buscarItemCatalogo(tipo, itemId)
                    .orElseThrow(() -> new ItemCatalogoNaoEncontradoException(tipo, itemId));
            catalogo.put(tipo + "/" + itemId, new ItemCatalogo(tipo, itemId, atual.nome(), atual.descricao(),
                    atual.precoUnitario(), false, atual.quantidadeEstoque(), atual.tempoEstimadoMinutos()));
        }

        @Override
        public ItemCatalogo reporEstoque(String itemId, int quantidade) {
            ItemCatalogo atual = buscarItemCatalogo(TipoItem.PECA, itemId)
                    .orElseThrow(() -> new ItemCatalogoNaoEncontradoException(TipoItem.PECA, itemId));
            return ajustarEstoque(itemId, atual.quantidadeEstoque() + quantidade);
        }

        @Override
        public ItemCatalogo ajustarEstoque(String itemId, int quantidadeEstoque) {
            ItemCatalogo atual = buscarItemCatalogo(TipoItem.PECA, itemId)
                    .orElseThrow(() -> new ItemCatalogoNaoEncontradoException(TipoItem.PECA, itemId));
            ItemCatalogo novo = new ItemCatalogo(TipoItem.PECA, itemId, atual.nome(), atual.descricao(),
                    atual.precoUnitario(), atual.ativo(), quantidadeEstoque, null);
            catalogo.put("PECA/" + itemId, novo);
            return novo;
        }
    }
}
