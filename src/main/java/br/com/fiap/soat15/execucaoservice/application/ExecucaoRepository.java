package br.com.fiap.soat15.execucaoservice.application;

import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.AgendamentoExecucao;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExecucaoRepository {
    AgendamentoExecucao agendarExecucao(AgendarExecucaoCommand comando, Instant agora);

    Diagnostico iniciarDiagnostico(String osId, Instant agora);

    Diagnostico atualizarItens(String osId, long versaoEsperada, List<ItemSolicitado> itens, Instant agora);

    Optional<Diagnostico> buscarDiagnostico(String osId);

    void salvarItemCatalogo(ItemCatalogo item);

    Optional<ItemCatalogo> buscarItemCatalogo(TipoItem tipo, String itemId);
}
