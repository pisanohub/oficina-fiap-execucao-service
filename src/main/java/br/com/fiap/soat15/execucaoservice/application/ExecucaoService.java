package br.com.fiap.soat15.execucaoservice.application;

import br.com.fiap.soat15.execucaoservice.domain.model.Cotacao;
import br.com.fiap.soat15.execucaoservice.domain.model.AgendamentoExecucao;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCotado;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ExecucaoService {
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
