package br.com.fiap.soat15.execucaoservice.adapter.in.web;

import br.com.fiap.soat15.execucaoservice.domain.model.Cotacao;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ExecucaoTrabalho;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

public interface ExecucaoApi {
    String CABECALHO_PROXIMA_PAGINA = "X-Proxima-Pagina";
    String TAG_CATALOGO = "Catálogo";
    String TAG_ESTOQUE = "Estoque";
    String TAG_DIAGNOSTICO = "Diagnóstico";
    String TAG_COTACAO = "Cotação";
    String TAG_EXECUCOES = "Execuções";

    String DESCRICAO_OS_ID = "Identificador da ordem de serviço (OS).";
    String DESCRICAO_TIPO = "Tipo do item: PECA ou SERVICO. Outro valor retorna 400.";
    String DESCRICAO_ITEM_ID = "Identificador textual do item no catálogo.";

    @Operation(tags = TAG_EXECUCOES, summary = "Listar a fila de trabalho",
            description = """
                    Lista as execuções. Sem `limite`, devolve todas. Com `limite` (1 a 100), devolve uma página \
                    e informa o token da próxima no cabeçalho `X-Proxima-Pagina`, ausente na última página. \
                    Esse token é opaco e deve ser enviado como `pagina` na chamada seguinte. O corpo é sempre a lista.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de execuções (ou uma página dela).",
                    headers = @Header(name = CABECALHO_PROXIMA_PAGINA,
                            description = "Token da próxima página. Só aparece quando `limite` é informado e há mais itens.",
                            schema = @Schema(type = "string", example = "eyJwayI6Ik9TIzEyMyIsInNrIjoiRVhFQ1VDQU8ifQ"))),
            @ApiResponse(responseCode = "400", description = "limite fora de 1 a 100, não numérico, pagina sem limite ou pagina inválida.")
    })
    ResponseEntity<List<ExecucaoTrabalho>> listarExecucoes(
            @Parameter(description = "Tamanho da página, de 1 a 100. Sem ele, a lista vem completa.", example = "2")
            Integer limite,
            @Parameter(description = "Token opaco recebido em X-Proxima-Pagina. Exige `limite`.")
            String pagina);

    @Operation(tags = TAG_EXECUCOES, summary = "Consultar uma execução",
            description = "Devolve a execução da OS com status, itens e datas de agendamento, início e conclusão.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Execução encontrada."),
            @ApiResponse(responseCode = "404", description = "Execução não encontrada: 123")
    })
    ExecucaoTrabalho consultarExecucao(@Parameter(description = DESCRICAO_OS_ID, example = "123") String osId);

    @Operation(tags = TAG_EXECUCOES, summary = "Iniciar a execução",
            description = """
                    Move a execução de AGENDADA para EM_EXECUCAO e publica `ExecucaoIniciada` em \
                    `execucao-resultados`. Idempotente: se já estiver EM_EXECUCAO, devolve o registro atual.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Execução em andamento."),
            @ApiResponse(responseCode = "404", description = "Execução não encontrada: 123"),
            @ApiResponse(responseCode = "409", description = "Execução não pode ser iniciada no estado atual")
    })
    ExecucaoTrabalho iniciarExecucao(@Parameter(description = DESCRICAO_OS_ID, example = "123") String osId);

    @Operation(tags = TAG_EXECUCOES, summary = "Concluir a execução",
            description = """
                    Move a execução de EM_EXECUCAO para CONCLUIDA e publica `ExecucaoConcluida` em \
                    `execucao-resultados`. Idempotente: se já estiver CONCLUIDA, devolve o registro atual.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Execução concluída."),
            @ApiResponse(responseCode = "404", description = "Execução não encontrada: 123"),
            @ApiResponse(responseCode = "409", description = "Execução precisa estar em execução para ser concluída")
    })
    ExecucaoTrabalho concluirExecucao(@Parameter(description = DESCRICAO_OS_ID, example = "123") String osId);

    @Operation(tags = TAG_CATALOGO, summary = "Cadastrar peça ou serviço",
            description = """
                    Cadastra um item no catálogo. PECA exige `quantidadeEstoque` (inteiro ≥ 0) e não aceita \
                    `tempoEstimadoMinutos`; SERVICO não tem estoque. Preço ≥ 0 com no máximo 2 casas decimais \
                    e `descricao` com até 300 caracteres.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    schema = @Schema(implementation = ItemCatalogo.class),
                    examples = {
                            @ExampleObject(name = "Peça", value = """
                                    {"tipo":"PECA","itemId":"filtro-oleo","nome":"Filtro de óleo",\
                                    "descricao":"Filtro de óleo original","precoUnitario":45.50,"ativo":true,\
                                    "quantidadeEstoque":10}"""),
                            @ExampleObject(name = "Serviço", value = """
                                    {"tipo":"SERVICO","itemId":"troca-oleo","nome":"Troca de óleo",\
                                    "descricao":"Inclui filtro","precoUnitario":90.00,"ativo":true,\
                                    "tempoEstimadoMinutos":45}""")
                    })))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Item cadastrado."),
            @ApiResponse(responseCode = "400", description = "Item de catálogo inválido (campos obrigatórios, preço, estoque ou tempo estimado)."),
            @ApiResponse(responseCode = "409", description = "Já existe um item com o mesmo tipo e itemId.")
    })
    ResponseEntity<Void> salvarItem(ItemCatalogo item);

    @Operation(tags = TAG_CATALOGO, summary = "Listar o catálogo",
            description = """
                    Lista peças e serviços ordenados por tipo e itemId. Sem `ativo=true`, traz também os itens \
                    inativos. O filtro `nome` busca por parte do nome, sem diferenciar acento nem maiúsculas.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Itens do catálogo que atendem aos filtros."),
            @ApiResponse(responseCode = "400", description = "Valor inválido para tipo ou ativo.")
    })
    List<ItemCatalogo> listarCatalogo(
            @Parameter(description = "Filtra por tipo: PECA ou SERVICO.") TipoItem tipo,
            @Parameter(description = "Filtra por itens ativos (true) ou inativos (false).", example = "true") Boolean ativo,
            @Parameter(description = "Parte do nome, sem diferenciar acento nem maiúsculas.", example = "oleo") String nome);

    @Operation(tags = TAG_CATALOGO, summary = "Consultar um item do catálogo",
            description = "Devolve o item, com o estoque atual quando for peça.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Item encontrado."),
            @ApiResponse(responseCode = "400", description = "Valor inválido para tipo."),
            @ApiResponse(responseCode = "404", description = "Item de catálogo não encontrado: PECA/filtro-oleo")
    })
    ItemCatalogo consultarItem(@Parameter(description = DESCRICAO_TIPO, example = "PECA") TipoItem tipo,
                               @Parameter(description = DESCRICAO_ITEM_ID, example = "filtro-oleo") String itemId);

    @Operation(tags = TAG_CATALOGO, summary = "Atualizar um item do catálogo",
            description = """
                    Substitui `nome`, `descricao`, `precoUnitario`, `ativo` e, para serviço, `tempoEstimadoMinutos`. \
                    `descricao` e `tempoEstimadoMinutos` ausentes são removidos. Não altera o estoque. Para \
                    reativar um item inativado, envie `ativo: true`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Item atualizado."),
            @ApiResponse(responseCode = "400", description = "Dados inválidos (ativo ausente, preço, descrição, tempo estimado em peça) ou tipo inválido."),
            @ApiResponse(responseCode = "404", description = "Item de catálogo não encontrado: PECA/filtro-oleo")
    })
    ItemCatalogo atualizarItem(@Parameter(description = DESCRICAO_TIPO, example = "PECA") TipoItem tipo,
                               @Parameter(description = DESCRICAO_ITEM_ID, example = "filtro-oleo") String itemId,
                               AtualizarItemCatalogoRequest request);

    @Operation(tags = TAG_CATALOGO, summary = "Inativar um item do catálogo",
            description = """
                    Soft delete: marca o item como inativo, sem apagar cadastro nem estoque. Repetir a chamada \
                    também retorna 204. Itens inativos são recusados no diagnóstico, na cotação e no agendamento.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Item inativado."),
            @ApiResponse(responseCode = "400", description = "Valor inválido para tipo."),
            @ApiResponse(responseCode = "404", description = "Item de catálogo não encontrado: PECA/filtro-oleo")
    })
    ResponseEntity<Void> inativarItem(@Parameter(description = DESCRICAO_TIPO, example = "PECA") TipoItem tipo,
                                      @Parameter(description = DESCRICAO_ITEM_ID, example = "filtro-oleo") String itemId);

    @Operation(tags = TAG_ESTOQUE, summary = "Repor estoque de uma peça",
            description = "Soma `quantidade` (1 a 1.000.000) ao estoque da peça, de forma atômica. Prefira esta rota para entradas de estoque.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Peça com o estoque atualizado."),
            @ApiResponse(responseCode = "400", description = "Quantidade fora de 1 a 1.000.000, tipo inválido ou item do tipo SERVICO."),
            @ApiResponse(responseCode = "404", description = "Item de catálogo não encontrado: PECA/filtro-oleo")
    })
    ItemCatalogo reporEstoque(@Parameter(description = "Tipo do item. Só PECA tem estoque.", example = "PECA") TipoItem tipo,
                              @Parameter(description = DESCRICAO_ITEM_ID, example = "filtro-oleo") String itemId,
                              ReporEstoqueRequest request);

    @Operation(tags = TAG_ESTOQUE, summary = "Ajustar estoque de uma peça",
            description = """
                    Define o estoque da peça (`quantidadeEstoque` de 0 a 1.000.000), por exemplo após contagem \
                    física. Sobrescreve o valor e pode apagar uma reserva feita ao mesmo tempo.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Peça com o estoque ajustado."),
            @ApiResponse(responseCode = "400", description = "quantidadeEstoque ausente ou fora de 0 a 1.000.000, tipo inválido ou item do tipo SERVICO."),
            @ApiResponse(responseCode = "404", description = "Item de catálogo não encontrado: PECA/filtro-oleo")
    })
    ItemCatalogo ajustarEstoque(@Parameter(description = "Tipo do item. Só PECA tem estoque.", example = "PECA") TipoItem tipo,
                                @Parameter(description = DESCRICAO_ITEM_ID, example = "filtro-oleo") String itemId,
                                AjustarEstoqueRequest request);

    @Operation(tags = TAG_DIAGNOSTICO, summary = "Iniciar o diagnóstico da OS",
            description = "Cria o diagnóstico na versão 1, sem itens, ou devolve o existente. Não tem corpo.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Diagnóstico criado ou já existente."))
    Diagnostico iniciar(@Parameter(description = DESCRICAO_OS_ID, example = "123") String osId);

    @Operation(tags = TAG_DIAGNOSTICO, summary = "Substituir os itens do diagnóstico",
            description = """
                    Substitui a lista de itens com controle otimista de versão: `versaoEsperada` deve ser a versão \
                    atual, e a resposta traz a versão incrementada. De 1 a 20 itens, sem repetir tipo e itemId, \
                    quantidade positiva, e todos precisam existir e estar ativos no catálogo. Guarda só ID e \
                    quantidade, nunca preço.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Diagnóstico atualizado com a nova versão."),
            @ApiResponse(responseCode = "400", description = "Itens inválidos, repetidos, ausentes ou inativos no catálogo, ou versaoEsperada não positiva."),
            @ApiResponse(responseCode = "404", description = "Diagnóstico não encontrado para a OS 123"),
            @ApiResponse(responseCode = "409", description = "versaoEsperada diferente da versão atual do diagnóstico.")
    })
    Diagnostico atualizar(@Parameter(description = DESCRICAO_OS_ID, example = "123") String osId,
                          AtualizarItensRequest request);

    @Operation(tags = TAG_DIAGNOSTICO, summary = "Consultar o diagnóstico da OS",
            description = "Devolve os itens e a versão atuais. Usado pelo OS Service antes de pedir o orçamento.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Diagnóstico encontrado."),
            @ApiResponse(responseCode = "404", description = "Diagnóstico não encontrado para a OS 123")
    })
    Diagnostico consultar(@Parameter(description = DESCRICAO_OS_ID, example = "123") String osId);

    @Operation(tags = TAG_COTACAO, summary = "Cotar preços dos itens",
            description = """
                    Devolve os preços atuais do catálogo (moeda BRL) para os itens informados. Não cria orçamento \
                    nem reserva estoque. O OS Service guarda esses valores como snapshot nos itens da OS e os envia ao \
                    Billing no comando GerarOrcamento (ADR-P4).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cotação com o preço unitário atual de cada item."),
            @ApiResponse(responseCode = "400", description = "Item ausente ou inativo: PECA/nao-existe")
    })
    Cotacao cotar(CotacaoRequest request);

    @Schema(description = "Dados para atualizar um item do catálogo. Substitui os dados atuais, exceto o estoque.")
    record AtualizarItemCatalogoRequest(
            @Schema(description = "Nome do item.", example = "Filtro de óleo", requiredMode = Schema.RequiredMode.REQUIRED)
            String nome,
            @Schema(description = "Descrição opcional, até 300 caracteres. Ausente remove a atual.", example = "Filtro de óleo original")
            String descricao,
            @Schema(description = "Preço unitário em reais, ≥ 0 e com até 2 casas decimais.", example = "45.50",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            BigDecimal precoUnitario,
            @Schema(description = "Se o item está ativo. false inativa, true reativa.", example = "true",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Boolean ativo,
            @Schema(description = "Tempo estimado em minutos (> 0). Só para SERVICO; ausente remove o atual.",
                    example = "45", nullable = true)
            Integer tempoEstimadoMinutos) {
    }

    @Schema(description = "Quantidade a somar ao estoque da peça.")
    record ReporEstoqueRequest(
            @Schema(description = "Unidades a adicionar, de 1 a 1.000.000.", example = "5", minimum = "1", maximum = "1000000",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            int quantidade) {
    }

    @Schema(description = "Novo valor absoluto do estoque da peça.")
    record AjustarEstoqueRequest(
            @Schema(description = "Estoque final, de 0 a 1.000.000.", example = "10", minimum = "0", maximum = "1000000",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Integer quantidadeEstoque) {
    }

    @Schema(description = "Nova lista de itens do diagnóstico, com a versão que o cliente leu por último.")
    record AtualizarItensRequest(
            @Schema(description = "Versão atual do diagnóstico (> 0). Se estiver desatualizada, retorna 409.", example = "1",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            long versaoEsperada,
            @ArraySchema(arraySchema = @Schema(description = "De 1 a 20 itens, sem repetir tipo e itemId."),
                    minItems = 1, maxItems = 20)
            List<ItemSolicitado> itens) {
    }

    @Schema(description = "Itens a cotar.")
    record CotacaoRequest(
            @ArraySchema(arraySchema = @Schema(description = "De 1 a 20 itens, sem repetir tipo e itemId."),
                    minItems = 1, maxItems = 20)
            List<ItemSolicitado> itens) {
    }
}
