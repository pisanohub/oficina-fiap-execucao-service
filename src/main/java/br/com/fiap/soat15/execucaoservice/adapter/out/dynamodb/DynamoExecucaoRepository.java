package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.domain.model.ExecucaoTrabalho;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import br.com.fiap.soat15.execucaoservice.application.DiagnosticoNaoEncontradoException;
import br.com.fiap.soat15.execucaoservice.application.ExecucaoJaAgendadaException;
import br.com.fiap.soat15.execucaoservice.application.PecaIndisponivelException;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.contract.CancelarExecucaoCommand;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import br.com.fiap.soat15.execucaoservice.domain.model.AgendamentoExecucao;
import br.com.fiap.soat15.execucaoservice.application.ExecucaoRepository;
import br.com.fiap.soat15.execucaoservice.application.ItemCatalogoExistenteException;
import br.com.fiap.soat15.execucaoservice.application.VersaoDiagnosticoConflitanteException;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class DynamoExecucaoRepository implements ExecucaoRepository {
    private final DynamoDbClient dynamo;
    private final String tableName;

    public DynamoExecucaoRepository(DynamoDbClient dynamo, @Value("${dynamodb.table-name}") String tableName) {
        this.dynamo = dynamo;
        this.tableName = tableName;
    }

    @Override
    public AgendamentoExecucao agendarExecucao(AgendarExecucaoCommand comando, Instant agora) {
        Map<String, AttributeValue> chaveOperacao = Map.of(
                "pk", texto("OPERACAO#" + comando.operationId()), "sk", texto("AGENDAMENTO"));
        Map<String, AttributeValue> existente = buscar(chaveOperacao);
        if (!existente.isEmpty()) {
            return mapearAgendamentoValidado(existente, comando);
        }

        String osId = comando.osId();
        AgendarExecucaoCommand.Payload payload = comando.payload();
        AttributeValue itens = AttributeValue.builder().l(payload.itens().stream()
                .map(this::itemParaAtributo).toList()).build();
        Map<String, AttributeValue> operacao = new HashMap<>(chaveOperacao);
        operacao.put("osId", texto(osId));
        operacao.put("operationId", texto(comando.operationId()));
        operacao.put("sagaId", texto(comando.sagaId()));
        operacao.put("orcamentoId", texto(payload.orcamentoId()));
        operacao.put("versaoOrcamento", numero(payload.versao()));
        operacao.put("pagamentoId", texto(payload.pagamentoId()));
        operacao.put("itens", itens);
        operacao.put("agendadoEm", texto(agora.toString()));

        Map<String, AttributeValue> execucao = new HashMap<>(chaveExecucao(osId));
        execucao.put("status", texto("AGENDADA"));
        execucao.put("operationId", texto(comando.operationId()));
        execucao.put("sagaId", texto(comando.sagaId()));
        execucao.put("orcamentoId", texto(payload.orcamentoId()));
        execucao.put("versaoOrcamento", numero(payload.versao()));
        execucao.put("pagamentoId", texto(payload.pagamentoId()));
        execucao.put("agendadoEm", texto(agora.toString()));
        execucao.put("itens", itens);

        Map<String, AttributeValue> outbox = new HashMap<>(Map.of(
                "pk", texto("OUTBOX#" + comando.operationId()), "sk", texto("RESULTADO")));
        outbox.put("type", texto("ExecucaoAgendada"));
        outbox.put("operationId", texto(comando.operationId()));
        outbox.put("sagaId", texto(comando.sagaId()));
        outbox.put("osId", texto(osId));
        outbox.put("causationId", texto(comando.messageId()));
        outbox.put("occurredAt", texto(agora.toString()));
        outbox.put("orcamentoId", texto(payload.orcamentoId()));
        outbox.put("versaoOrcamento", numero(payload.versao()));
        outbox.put("pagamentoId", texto(payload.pagamentoId()));
        outbox.put("published", AttributeValue.builder().bool(false).build());

        List<TransactWriteItem> writes = new ArrayList<>();
        List<ItemSolicitado> pecas = payload.itens().stream()
                .filter(item -> item.tipo() == TipoItem.PECA).toList();
        for (ItemSolicitado item : pecas) {
            writes.add(TransactWriteItem.builder().update(Update.builder()
                    .tableName(tableName).key(chaveCatalogo(item.tipo(), item.itemId()))
                    .updateExpression("SET quantidadeEstoque = quantidadeEstoque - :quantidade")
                    .conditionExpression("attribute_exists(pk) AND ativo = :ativo AND quantidadeEstoque >= :quantidade")
                    .expressionAttributeValues(Map.of(":quantidade", numero(item.quantidade()),
                            ":ativo", AttributeValue.builder().bool(true).build()))
                    .build()).build());
        }
        writes.add(putSeAusente(execucao));
        writes.add(putSeAusente(operacao));
        writes.add(putSeAusente(outbox));
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(writes).build());
            return mapearAgendamento(operacao);
        } catch (TransactionCanceledException conflito) {
            // Um retry concorrente pode ter gravado a mesma operação antes desta transação.
            Map<String, AttributeValue> concluida = buscar(chaveOperacao);
            if (!concluida.isEmpty()) {
                return mapearAgendamentoValidado(concluida, comando);
            }
            List<CancellationReason> motivos = conflito.cancellationReasons();
            if (motivos != null) {
                for (int i = 0; i < pecas.size() && i < motivos.size(); i++) {
                    if ("ConditionalCheckFailed".equals(motivos.get(i).code())) {
                        String itemId = pecas.get(i).itemId();
                        Map<String, AttributeValue> peca = buscar(chaveCatalogo(TipoItem.PECA, itemId));
                        if (peca.isEmpty() || !peca.containsKey("ativo") || !Boolean.TRUE.equals(peca.get("ativo").bool())
                                || !peca.containsKey("quantidadeEstoque")) {
                            throw new IllegalStateException("Peça ausente, inativa ou sem estoque cadastrado: " + itemId, conflito);
                        }
                        if (Long.parseLong(peca.get("quantidadeEstoque").n()) >= pecas.get(i).quantidade()) {
                            throw conflito;
                        }
                        operacao.put("resultado", texto("REJEITADA"));
                        operacao.put("itemIndisponivel", texto(itemId));
                        outbox.put("type", texto("ExecucaoRejeitada"));
                        outbox.put("motivo", texto("ESTOQUE_INSUFICIENTE"));
                        outbox.put("itemId", texto(itemId));
                        try {
                            dynamo.transactWriteItems(TransactWriteItemsRequest.builder()
                                    .transactItems(putSeAusente(operacao), putSeAusente(outbox)).build());
                        } catch (TransactionCanceledException corrida) {
                            Map<String, AttributeValue> resultado = buscar(chaveOperacao);
                            if (!resultado.isEmpty()) return mapearAgendamentoValidado(resultado, comando);
                            throw corrida;
                        }
                        throw new PecaIndisponivelException(itemId, conflito);
                    }
                }
                if (motivos.size() > pecas.size()
                        && "ConditionalCheckFailed".equals(motivos.get(pecas.size()).code())) {
                    throw new ExecucaoJaAgendadaException(osId, conflito);
                }
            }
            throw conflito;
        }
    }

    @Override
    public void cancelarExecucao(CancelarExecucaoCommand comando, Instant agora) {
        var chaveOperacao = Map.of("pk", texto("OPERACAO#" + comando.operationId()), "sk", texto("CANCELAMENTO"));
        var anterior = buscar(chaveOperacao);
        if (!anterior.isEmpty()) {
            validarCancelamento(anterior, comando);
            return;
        }
        var chave = chaveExecucao(comando.osId());
        var execucao = buscar(chave);
        if (execucao.isEmpty()) throw new NoSuchElementException("Execução não encontrada: " + comando.osId());
        if (!comando.sagaId().equals(execucao.get("sagaId").s())) throw new IllegalArgumentException("Saga não corresponde à execução");
        String status = execucao.get("status").s();
        boolean cancelar = "AGENDADA".equals(status);
        boolean cancelada = "CANCELADA".equals(status);
        if (!cancelar && !cancelada && !"EM_EXECUCAO".equals(status) && !"CONCLUIDA".equals(status)) {
            throw new IllegalStateException("Estado de execução desconhecido: " + status);
        }
        String tipo = cancelar || cancelada ? "ExecucaoCancelada" : "CancelamentoExecucaoRecusado";
        var operacao = new HashMap<>(chaveOperacao);
        operacao.put("osId", texto(comando.osId()));
        operacao.put("sagaId", texto(comando.sagaId()));
        operacao.put("resultado", texto(tipo));
        Map<String, AttributeValue> evento = new HashMap<>(Map.of("pk", texto("OUTBOX#" + comando.operationId()), "sk", texto("RESULTADO")));
        evento.put("type", texto(tipo));
        evento.put("operationId", texto(comando.operationId()));
        evento.put("sagaId", texto(comando.sagaId()));
        evento.put("osId", texto(comando.osId()));
        evento.put("causationId", texto(comando.messageId()));
        evento.put("occurredAt", texto(agora.toString()));
        evento.put("orcamentoId", execucao.get("orcamentoId"));
        evento.put("versaoOrcamento", execucao.get("versaoOrcamento"));
        evento.put("pagamentoId", execucao.get("pagamentoId"));
        if (!cancelar && !cancelada) evento.put("motivo", texto("ESTADO_NAO_PERMITE_CANCELAMENTO"));
        evento.put("published", AttributeValue.builder().bool(false).build());
        List<TransactWriteItem> escritas = new ArrayList<>();
        if (cancelar) {
            for (var valor : execucao.get("itens").l()) {
                var item = valor.m();
                if (!"PECA".equals(item.get("tipo").s())) continue;
                escritas.add(TransactWriteItem.builder().update(Update.builder().tableName(tableName)
                        .key(chaveCatalogo(TipoItem.PECA, item.get("itemId").s()))
                        .updateExpression("SET quantidadeEstoque = quantidadeEstoque + :quantidade")
                        .conditionExpression("attribute_exists(pk) AND attribute_exists(quantidadeEstoque)")
                        .expressionAttributeValues(Map.of(":quantidade", item.get("quantidade"))).build()).build());
            }
            escritas.add(TransactWriteItem.builder().update(Update.builder().tableName(tableName).key(chave)
                    .updateExpression("SET #status = :novo, canceladaEm = :agora")
                    .conditionExpression("#status = :atual").expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(":novo", texto("CANCELADA"), ":atual", texto(status), ":agora", texto(agora.toString()))).build()).build());
        } else {
            escritas.add(TransactWriteItem.builder().conditionCheck(ConditionCheck.builder().tableName(tableName).key(chave)
                    .conditionExpression("#status = :atual").expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(":atual", texto(status))).build()).build());
        }
        escritas.add(putSeAusente(operacao));
        escritas.add(putSeAusente(evento));
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(escritas).build());
        } catch (TransactionCanceledException falha) {
            var concluida = buscar(chaveOperacao);
            if (!concluida.isEmpty()) { validarCancelamento(concluida, comando); return; }
            throw falha;
        }
    }

    private void validarCancelamento(Map<String, AttributeValue> operacao, CancelarExecucaoCommand comando) {
        if (!comando.osId().equals(operacao.get("osId").s()) || !comando.sagaId().equals(operacao.get("sagaId").s())) {
            throw new IllegalArgumentException("operationId de cancelamento reutilizado com dados diferentes");
        }
    }

    @Override
    public List<ExecucaoTrabalho> listarExecucoes() {
        List<ExecucaoTrabalho> resultado = new ArrayList<>();
        Map<String, AttributeValue> cursor = Map.of();
        do {
            var request = ScanRequest.builder().tableName(tableName)
                    .filterExpression("begins_with(pk, :prefixo) AND sk = :sk")
                    .expressionAttributeValues(Map.of(":prefixo", texto("OS#"), ":sk", texto("EXECUCAO")));
            if (cursor != null && !cursor.isEmpty()) request.exclusiveStartKey(cursor);
            var pagina = dynamo.scan(request.build());
            for (var item : pagina.items()) resultado.add(mapearExecucao(item));
            cursor = pagina.lastEvaluatedKey();
        } while (cursor != null && !cursor.isEmpty());
        return resultado;
    }

    @Override
    public Optional<ExecucaoTrabalho> buscarExecucao(String osId) {
        var item = buscar(chaveExecucao(osId));
        return item.isEmpty() ? Optional.empty() : Optional.of(mapearExecucao(item));
    }

    @Override
    public ExecucaoTrabalho iniciarExecucao(String osId, Instant agora) {
        var chave = chaveExecucao(osId);
        var item = buscar(chave);
        if (item.isEmpty()) throw new NoSuchElementException("Execução não encontrada: " + osId);
        if ("EM_EXECUCAO".equals(item.get("status").s())) return mapearExecucao(item);
        if (!"AGENDADA".equals(item.get("status").s())) throw new IllegalStateException("Execução não pode ser iniciada no estado atual");
        String operacao = "iniciar-execucao-" + UUID.nameUUIDFromBytes(osId.getBytes(StandardCharsets.UTF_8));
        Map<String, AttributeValue> evento = new HashMap<>(Map.of("pk", texto("OUTBOX#" + operacao), "sk", texto("RESULTADO")));
        evento.put("type", texto("ExecucaoIniciada"));
        evento.put("operationId", texto(operacao));
        evento.put("sagaId", item.get("sagaId"));
        evento.put("osId", texto(osId));
        evento.put("causationId", texto(operacao));
        evento.put("occurredAt", texto(agora.toString()));
        evento.put("orcamentoId", item.get("orcamentoId"));
        evento.put("versaoOrcamento", item.get("versaoOrcamento"));
        evento.put("pagamentoId", item.get("pagamentoId"));
        evento.put("published", AttributeValue.builder().bool(false).build());
        var atualizar = TransactWriteItem.builder().update(Update.builder().tableName(tableName).key(chave)
                .updateExpression("SET #status = :iniciada, iniciadaEm = :agora")
                .conditionExpression("#status = :agendada")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":iniciada", texto("EM_EXECUCAO"), ":agendada", texto("AGENDADA"), ":agora", texto(agora.toString())))
                .build()).build();
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(atualizar, putSeAusente(evento)).build());
        } catch (TransactionCanceledException falha) {
            var atual = buscar(chave);
            if (!atual.isEmpty() && "EM_EXECUCAO".equals(atual.get("status").s())) return mapearExecucao(atual);
            throw falha;
        }
        var atual = new HashMap<>(item);
        atual.put("status", texto("EM_EXECUCAO"));
        atual.put("iniciadaEm", texto(agora.toString()));
        return mapearExecucao(atual);
    }

    @Override
    public ExecucaoTrabalho concluirExecucao(String osId, Instant agora) {
        var chave = chaveExecucao(osId);
        var item = buscar(chave);
        if (item.isEmpty()) throw new NoSuchElementException("Execução não encontrada: " + osId);
        if ("CONCLUIDA".equals(item.get("status").s())) return mapearExecucao(item);
        if (!"EM_EXECUCAO".equals(item.get("status").s())) throw new IllegalStateException("Execução precisa estar em execução para ser concluída");
        String operacao = "concluir-execucao-" + UUID.nameUUIDFromBytes(osId.getBytes(StandardCharsets.UTF_8));
        Map<String, AttributeValue> evento = new HashMap<>(Map.of("pk", texto("OUTBOX#" + operacao), "sk", texto("RESULTADO")));
        evento.put("type", texto("ExecucaoConcluida"));
        evento.put("operationId", texto(operacao));
        evento.put("sagaId", item.get("sagaId"));
        evento.put("osId", texto(osId));
        evento.put("causationId", texto(operacao));
        evento.put("occurredAt", texto(agora.toString()));
        evento.put("iniciadaEm", item.get("iniciadaEm"));
        evento.put("orcamentoId", item.get("orcamentoId"));
        evento.put("versaoOrcamento", item.get("versaoOrcamento"));
        evento.put("pagamentoId", item.get("pagamentoId"));
        evento.put("published", AttributeValue.builder().bool(false).build());
        var atualizar = TransactWriteItem.builder().update(Update.builder().tableName(tableName).key(chave)
                .updateExpression("SET #status = :concluida, concluidaEm = :agora")
                .conditionExpression("#status = :iniciada")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":concluida", texto("CONCLUIDA"), ":iniciada", texto("EM_EXECUCAO"), ":agora", texto(agora.toString())))
                .build()).build();
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(atualizar, putSeAusente(evento)).build());
        } catch (TransactionCanceledException falha) {
            var atual = buscar(chave);
            if (!atual.isEmpty() && "CONCLUIDA".equals(atual.get("status").s())) return mapearExecucao(atual);
            if (!atual.isEmpty() && !"EM_EXECUCAO".equals(atual.get("status").s())) throw new IllegalStateException("Execução não pode ser concluída no estado atual", falha);
            throw falha;
        }
        var atual = new HashMap<>(item);
        atual.put("status", texto("CONCLUIDA"));
        atual.put("concluidaEm", texto(agora.toString()));
        return mapearExecucao(atual);
    }

    private ExecucaoTrabalho mapearExecucao(Map<String, AttributeValue> item) {
        var itens = item.get("itens").l().stream().map(valor -> {
            var campos = valor.m();
            return new ItemSolicitado(TipoItem.valueOf(campos.get("tipo").s()), campos.get("itemId").s(),
                    Integer.parseInt(campos.get("quantidade").n()));
        }).toList();
        return new ExecucaoTrabalho(item.get("pk").s().substring(3),
                item.get("status").s(), item.get("sagaId").s(), itens, Instant.parse(item.get("agendadoEm").s()),
                item.containsKey("iniciadaEm") ? Instant.parse(item.get("iniciadaEm").s()) : null,
                item.containsKey("concluidaEm") ? Instant.parse(item.get("concluidaEm").s()) : null);
    }

    private TransactWriteItem putSeAusente(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder().put(Put.builder().tableName(tableName)
                .item(item).conditionExpression("attribute_not_exists(pk)").build()).build();
    }

    private Map<String, AttributeValue> chaveExecucao(String osId) {
        return Map.of("pk", texto("OS#" + osId), "sk", texto("EXECUCAO"));
    }

    private AttributeValue itemParaAtributo(ItemSolicitado item) {
        return AttributeValue.builder().m(Map.of("tipo", texto(item.tipo().name()),
                "itemId", texto(item.itemId()), "quantidade", numero(item.quantidade()))).build();
    }

    private Set<ItemSolicitado> itensDaOperacao(Map<String, AttributeValue> operacao) {
        if (!operacao.containsKey("itens")) {
            throw new IllegalArgumentException("operationId sem itens registrados");
        }
        return operacao.get("itens").l().stream().map(valor -> {
            Map<String, AttributeValue> campos = valor.m();
            return new ItemSolicitado(TipoItem.valueOf(campos.get("tipo").s()),
                    campos.get("itemId").s(), Integer.parseInt(campos.get("quantidade").n()));
        }).collect(Collectors.toSet());
    }

    private AgendamentoExecucao mapearAgendamentoValidado(Map<String, AttributeValue> item,
                                                           AgendarExecucaoCommand comando) {
        AgendamentoExecucao agendamento = mapearAgendamento(item);
        if (!agendamento.osId().equals(comando.osId())
                || !agendamento.sagaId().equals(comando.sagaId())
                || !agendamento.orcamentoId().equals(comando.payload().orcamentoId())
                || agendamento.versaoOrcamento() != comando.payload().versao()
                || !agendamento.pagamentoId().equals(comando.payload().pagamentoId())
                || !itensDaOperacao(item).equals(Set.copyOf(comando.payload().itens()))) {
            throw new IllegalArgumentException("operationId reutilizado com dados diferentes");
        }
        if (item.containsKey("resultado") && "REJEITADA".equals(item.get("resultado").s())) {
            throw new PecaIndisponivelException(item.get("itemIndisponivel").s(), null);
        }
        return agendamento;
    }

    private AgendamentoExecucao mapearAgendamento(Map<String, AttributeValue> item) {
        return new AgendamentoExecucao(item.get("osId").s(), item.get("operationId").s(),
                item.get("sagaId").s(), item.get("orcamentoId").s(),
                Integer.parseInt(item.get("versaoOrcamento").n()), item.get("pagamentoId").s(),
                Instant.parse(item.get("agendadoEm").s()));
    }

    @Override
    public Diagnostico iniciarDiagnostico(String osId, Instant agora) {
        Optional<Diagnostico> existente = buscarDiagnostico(osId);
        if (existente.isPresent()) {
            return existente.get();
        }
        Map<String, AttributeValue> item = new HashMap<>(chaveDiagnostico(osId));
        item.put("status", texto("EM_DIAGNOSTICO"));
        item.put("versao", numero(1));
        item.put("itens", AttributeValue.builder().l(List.of()).build());
        item.put("atualizadoEm", texto(agora.toString()));
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pk)")
                    .build());
            return new Diagnostico(osId, 1, List.of(), agora);
        } catch (ConditionalCheckFailedException corrida) {
            return buscarDiagnostico(osId).orElseThrow(() -> corrida);
        }
    }

    @Override
    public Diagnostico atualizarItens(String osId, long versaoEsperada, List<ItemSolicitado> itens, Instant agora) {
        List<AttributeValue> itensDynamo = itens.stream().map(item -> AttributeValue.builder().m(Map.of(
                "tipo", texto(item.tipo().name()),
                "itemId", texto(item.itemId()),
                "quantidade", numero(item.quantidade())
        )).build()).toList();
        try {
            UpdateItemResponse response = dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(chaveDiagnostico(osId))
                    .updateExpression("SET #itens = :itens, #versao = :nova, #atualizado = :agora")
                    .conditionExpression("#versao = :esperada AND #status = :diagnostico")
                    .expressionAttributeNames(Map.of(
                            "#itens", "itens", "#versao", "versao", "#atualizado", "atualizadoEm", "#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":itens", AttributeValue.builder().l(itensDynamo).build(),
                            ":nova", numero(versaoEsperada + 1),
                            ":esperada", numero(versaoEsperada),
                            ":agora", texto(agora.toString()),
                            ":diagnostico", texto("EM_DIAGNOSTICO")))
                    .returnValues("ALL_NEW")
                    .build());
            return mapearDiagnostico(response.attributes());
        } catch (ConditionalCheckFailedException conflito) {
            if (buscarDiagnostico(osId).isEmpty()) {
                throw new DiagnosticoNaoEncontradoException(osId);
            }
            throw new VersaoDiagnosticoConflitanteException(osId);
        }
    }

    @Override
    public Optional<Diagnostico> buscarDiagnostico(String osId) {
        Map<String, AttributeValue> item = buscar(chaveDiagnostico(osId));
        return item.isEmpty() ? Optional.empty() : Optional.of(mapearDiagnostico(item));
    }

    @Override
    public void salvarItemCatalogo(ItemCatalogo catalogo) {
        Map<String, AttributeValue> item = new HashMap<>(chaveCatalogo(catalogo.tipo(), catalogo.itemId()));
        item.put("tipo", texto(catalogo.tipo().name()));
        item.put("itemId", texto(catalogo.itemId()));
        item.put("nome", texto(catalogo.nome()));
        item.put("precoUnitario", AttributeValue.builder().n(catalogo.precoUnitario().toPlainString()).build());
        item.put("ativo", AttributeValue.builder().bool(catalogo.ativo()).build());
        if (catalogo.quantidadeEstoque() != null) {
            item.put("quantidadeEstoque", numero(catalogo.quantidadeEstoque()));
        }
        try {
            dynamo.putItem(PutItemRequest.builder().tableName(tableName).item(item)
                    .conditionExpression("attribute_not_exists(pk)").build());
        } catch (ConditionalCheckFailedException existente) {
            throw new ItemCatalogoExistenteException(catalogo.itemId());
        }
    }

    @Override
    public Optional<ItemCatalogo> buscarItemCatalogo(TipoItem tipo, String itemId) {
        Map<String, AttributeValue> item = buscar(chaveCatalogo(tipo, itemId));
        if (item.isEmpty()) {
            return Optional.empty();
        }
        Integer quantidade = item.containsKey("quantidadeEstoque")
                ? Integer.valueOf(item.get("quantidadeEstoque").n()) : null;
        return Optional.of(new ItemCatalogo(tipo, itemId, item.get("nome").s(),
                new BigDecimal(item.get("precoUnitario").n()), item.get("ativo").bool(), quantidade));
    }

    private Map<String, AttributeValue> buscar(Map<String, AttributeValue> chave) {
        return dynamo.getItem(GetItemRequest.builder()
                .tableName(tableName).key(chave).consistentRead(true).build()).item();
    }

    private Diagnostico mapearDiagnostico(Map<String, AttributeValue> item) {
        List<ItemSolicitado> itens = new ArrayList<>();
        for (AttributeValue valor : item.get("itens").l()) {
            Map<String, AttributeValue> campos = valor.m();
            itens.add(new ItemSolicitado(TipoItem.valueOf(campos.get("tipo").s()),
                    campos.get("itemId").s(), Integer.parseInt(campos.get("quantidade").n())));
        }
        String osId = item.get("pk").s().substring("OS#".length());
        return new Diagnostico(osId, Long.parseLong(item.get("versao").n()), item.get("status").s(), itens,
                Instant.parse(item.get("atualizadoEm").s()));
    }

    private Map<String, AttributeValue> chaveDiagnostico(String osId) {
        return Map.of("pk", texto("OS#" + osId), "sk", texto("DIAGNOSTICO"));
    }

    private Map<String, AttributeValue> chaveCatalogo(TipoItem tipo, String itemId) {
        return Map.of("pk", texto(tipo.name() + "#" + itemId), "sk", texto("CADASTRO"));
    }

    private AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }

    private AttributeValue numero(long valor) {
        return AttributeValue.builder().n(Long.toString(valor)).build();
    }
}
