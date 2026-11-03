package br.com.fiap.soat15.execucaoservice.application;

import br.com.fiap.soat15.execucaoservice.domain.model.ExecucaoTrabalho;
import java.util.NoSuchElementException;
import br.com.fiap.soat15.execucaoservice.domain.model.Cotacao;
import br.com.fiap.soat15.execucaoservice.domain.model.AgendamentoExecucao;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.contract.CancelarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.domain.model.AtualizacaoItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCotado;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.PaginaExecucoes;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ExecucaoService {
    private static final int LIMITE_ESTOQUE = 1_000_000;
    private static final int LIMITE_PAGINA = 100;

    private final ExecucaoRepository repository;
    private final Clock clock;

    public ExecucaoService(ExecucaoRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public AgendamentoExecucao agendarExecucao(AgendarExecucaoCommand comando) {
        if (comando == null) {
            throw new IllegalArgumentException("Comando é obrigatório");
        }
        validarItens(comando.payload().itens());
        return repository.agendarExecucao(comando, clock.instant());
    }

    public void cancelarExecucao(CancelarExecucaoCommand comando) {
        if (comando == null) throw new IllegalArgumentException("Comando é obrigatório");
        repository.cancelarExecucao(comando, clock.instant());
    }

    public List<ExecucaoTrabalho> listarExecucoes() {
        return repository.listarExecucoes();
    }

    public PaginaExecucoes listarExecucoes(int limite, String pagina) {
        if (limite < 1 || limite > LIMITE_PAGINA) {
            throw new IllegalArgumentException("limite deve estar entre 1 e " + LIMITE_PAGINA);
        }
        return repository.listarExecucoes(limite, pagina);
    }

    public ExecucaoTrabalho consultarExecucao(String osId) {
        validarOsId(osId);
        return repository.buscarExecucao(osId.trim()).orElseThrow(() -> new NoSuchElementException("Execução não encontrada: " + osId));
    }

    public ExecucaoTrabalho iniciarExecucao(String osId) {
        validarOsId(osId);
        return repository.iniciarExecucao(osId.trim(), clock.instant());
    }

    public ExecucaoTrabalho concluirExecucao(String osId) {
        validarOsId(osId);
        return repository.concluirExecucao(osId.trim(), clock.instant());
    }

    public Diagnostico iniciarDiagnostico(String osId) {
        validarOsId(osId);
        return repository.iniciarDiagnostico(osId.trim(), clock.instant());
    }

    public Diagnostico atualizarItens(String osId, long versaoEsperada, List<ItemSolicitado> itens) {
        validarOsId(osId);
        validarItens(itens);
        if (versaoEsperada <= 0) {
            throw new IllegalArgumentException("versaoEsperada deve ser positiva");
        }
        cotar(itens); // Garante que o diagnóstico só referencie itens ativos do catálogo.
        return repository.atualizarItens(osId.trim(), versaoEsperada, List.copyOf(itens), clock.instant());
    }

    public Diagnostico consultarDiagnostico(String osId) {
        validarOsId(osId);
        return repository.buscarDiagnostico(osId.trim())
                .orElseThrow(() -> new DiagnosticoNaoEncontradoException(osId));
    }

    public void salvarItemCatalogo(ItemCatalogo item) {
        if (item == null) {
            throw new IllegalArgumentException("Item de catálogo é obrigatório");
        }
        repository.salvarItemCatalogo(item);
    }

    public List<ItemCatalogo> listarCatalogo(TipoItem tipo, Boolean ativo, String nome) {
        List<ItemCatalogo> itens = repository.listarItensCatalogo(tipo, ativo);
        if (nome == null || nome.isBlank()) {
            return itens;
        }
        String busca = normalizar(nome);
        return itens.stream().filter(item -> normalizar(item.nome()).contains(busca)).toList();
    }

    public ItemCatalogo consultarItemCatalogo(TipoItem tipo, String itemId) {
        validarItemId(tipo, itemId);
        return repository.buscarItemCatalogo(tipo, itemId.trim())
                .orElseThrow(() -> new ItemCatalogoNaoEncontradoException(tipo, itemId.trim()));
    }

    public ItemCatalogo atualizarItemCatalogo(TipoItem tipo, String itemId, String nome, String descricao,
                                              BigDecimal precoUnitario, Boolean ativo, Integer tempoEstimadoMinutos) {
        validarItemId(tipo, itemId);
        if (ativo == null) {
            throw new IllegalArgumentException("ativo é obrigatório");
        }
        if (tipo == TipoItem.PECA && tempoEstimadoMinutos != null) {
            throw new IllegalArgumentException("Peça não possui tempo estimado");
        }
        return repository.atualizarItemCatalogo(tipo, itemId.trim(),
                new AtualizacaoItemCatalogo(nome, descricao, precoUnitario, ativo, tempoEstimadoMinutos));
    }

    public void inativarItemCatalogo(TipoItem tipo, String itemId) {
        validarItemId(tipo, itemId);
        repository.inativarItemCatalogo(tipo, itemId.trim());
    }

    public ItemCatalogo reporEstoque(TipoItem tipo, String itemId, int quantidade) {
        validarPeca(tipo, itemId);
        if (quantidade <= 0 || quantidade > LIMITE_ESTOQUE) {
            throw new IllegalArgumentException("quantidade deve estar entre 1 e " + LIMITE_ESTOQUE);
        }
        return repository.reporEstoque(itemId.trim(), quantidade);
    }

    public ItemCatalogo ajustarEstoque(TipoItem tipo, String itemId, Integer quantidadeEstoque) {
        validarPeca(tipo, itemId);
        if (quantidadeEstoque == null || quantidadeEstoque < 0 || quantidadeEstoque > LIMITE_ESTOQUE) {
            throw new IllegalArgumentException("quantidadeEstoque deve estar entre 0 e " + LIMITE_ESTOQUE);
        }
        return repository.ajustarEstoque(itemId.trim(), quantidadeEstoque);
    }

    public Cotacao cotar(List<ItemSolicitado> itens) {
        validarItens(itens);
        List<ItemCotado> cotados = itens.stream().map(item -> {
            ItemCatalogo catalogo = repository.buscarItemCatalogo(item.tipo(), item.itemId())
                    .filter(ItemCatalogo::ativo)
                    .orElseThrow(() -> new IllegalArgumentException("Item ausente ou inativo: " + item.tipo() + "/" + item.itemId()));
            return new ItemCotado(item.tipo(), item.itemId(), catalogo.nome(), item.quantidade(), catalogo.precoUnitario());
        }).toList();
        return new Cotacao("BRL", clock.instant(), cotados);
    }

    private String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private void validarItemId(TipoItem tipo, String itemId) {
        if (tipo == null || itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("tipo e itemId são obrigatórios");
        }
    }

    private void validarPeca(TipoItem tipo, String itemId) {
        validarItemId(tipo, itemId);
        if (tipo != TipoItem.PECA) {
            throw new IllegalArgumentException("Serviço não possui estoque");
        }
    }

    private void validarOsId(String osId) {
        if (osId == null || osId.isBlank()) {
            throw new IllegalArgumentException("osId é obrigatório");
        }
    }

    private void validarItens(List<ItemSolicitado> itens) {
        if (itens == null || itens.isEmpty() || itens.size() > 20) {
            throw new IllegalArgumentException("Informe entre 1 e 20 itens");
        }
        Set<String> chaves = new HashSet<>();
        for (ItemSolicitado item : itens) {
            if (item == null || !chaves.add(item.tipo() + "/" + item.itemId())) {
                throw new IllegalArgumentException("Itens nulos ou repetidos não são permitidos");
            }
        }
    }
}
