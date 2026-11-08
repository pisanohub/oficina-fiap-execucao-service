package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.ExecucaoRepository;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.contract.CancelarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.domain.model.AgendamentoExecucao;
import br.com.fiap.soat15.execucaoservice.domain.model.AtualizacaoItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ExecucaoTrabalho;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.PaginaExecucoes;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import br.com.fiap.soat15.execucaoservice.config.observability.ContextoRastreamento;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class DynamoExecucaoRepository implements ExecucaoRepository {
    private final DynamoAgendamentoRepository agendamentos;
    private final DynamoCicloExecucaoRepository ciclo;
    private final DynamoDiagnosticoRepository diagnosticos;
    private final DynamoCatalogoRepository catalogo;

    public DynamoExecucaoRepository(DynamoDbClient dynamo, @Value("${dynamodb.table-name}") String tableName,
                                    ContextoRastreamento rastreamento) {
        this.agendamentos = new DynamoAgendamentoRepository(dynamo, tableName, rastreamento);
        this.ciclo = new DynamoCicloExecucaoRepository(dynamo, tableName, rastreamento);
        this.diagnosticos = new DynamoDiagnosticoRepository(dynamo, tableName);
        this.catalogo = new DynamoCatalogoRepository(dynamo, tableName);
    }

    @Override
    public AgendamentoExecucao agendarExecucao(AgendarExecucaoCommand comando, Instant agora) {
        return agendamentos.agendarExecucao(comando, agora);
    }

    @Override
    public void cancelarExecucao(CancelarExecucaoCommand comando, Instant agora) {
        ciclo.cancelarExecucao(comando, agora);
    }

    @Override
    public List<ExecucaoTrabalho> listarExecucoes() {
        return ciclo.listarExecucoes();
    }

    @Override
    public PaginaExecucoes listarExecucoes(int limite, String pagina) {
        return ciclo.listarExecucoes(limite, pagina);
    }

    @Override
    public Optional<ExecucaoTrabalho> buscarExecucao(String osId) {
        return ciclo.buscarExecucao(osId);
    }

    @Override
    public ExecucaoTrabalho iniciarExecucao(String osId, Instant agora) {
        return ciclo.iniciarExecucao(osId, agora);
    }

    @Override
    public ExecucaoTrabalho concluirExecucao(String osId, Instant agora) {
        return ciclo.concluirExecucao(osId, agora);
    }

    @Override
    public Diagnostico iniciarDiagnostico(String osId, Instant agora) {
        return diagnosticos.iniciarDiagnostico(osId, agora);
    }

    @Override
    public Diagnostico atualizarItens(String osId, long versaoEsperada, List<ItemSolicitado> itens, Instant agora) {
        return diagnosticos.atualizarItens(osId, versaoEsperada, itens, agora);
    }

    @Override
    public Optional<Diagnostico> buscarDiagnostico(String osId) {
        return diagnosticos.buscarDiagnostico(osId);
    }

    @Override
    public void salvarItemCatalogo(ItemCatalogo item) {
        catalogo.salvarItemCatalogo(item);
    }

    @Override
    public Optional<ItemCatalogo> buscarItemCatalogo(TipoItem tipo, String itemId) {
        return catalogo.buscarItemCatalogo(tipo, itemId);
    }

    @Override
    public List<ItemCatalogo> listarItensCatalogo(TipoItem tipo, Boolean ativo) {
        return catalogo.listarItensCatalogo(tipo, ativo);
    }

    @Override
    public ItemCatalogo atualizarItemCatalogo(TipoItem tipo, String itemId, AtualizacaoItemCatalogo atualizacao) {
        return catalogo.atualizarItemCatalogo(tipo, itemId, atualizacao);
    }

    @Override
    public void inativarItemCatalogo(TipoItem tipo, String itemId) {
        catalogo.inativarItemCatalogo(tipo, itemId);
    }

    @Override
    public ItemCatalogo reporEstoque(String itemId, int quantidade) {
        return catalogo.reporEstoque(itemId, quantidade);
    }

    @Override
    public ItemCatalogo ajustarEstoque(String itemId, int quantidadeEstoque) {
        return catalogo.ajustarEstoque(itemId, quantidadeEstoque);
    }
}
