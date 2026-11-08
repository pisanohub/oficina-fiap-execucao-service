package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.ExecucaoJaAgendadaException;
import br.com.fiap.soat15.execucaoservice.application.PecaIndisponivelException;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.domain.model.AgendamentoExecucao;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.StatusExecucao;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoEventoExecucao;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import br.com.fiap.soat15.execucaoservice.domain.model.MotivoResultadoExecucao;
import br.com.fiap.soat15.execucaoservice.config.observability.ContextoRastreamento;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class DynamoAgendamentoRepository extends DynamoRepositorySupport {
    private final ContextoRastreamento rastreamento;

    DynamoAgendamentoRepository(DynamoDbClient dynamo, String tableName, ContextoRastreamento rastreamento) {
        super(dynamo, tableName);
        this.rastreamento = rastreamento;
    }

    public AgendamentoExecucao agendarExecucao(AgendarExecucaoCommand comando, Instant agora) {
        Map<String, AttributeValue> chaveOperacao = DynamoKeys.chaveOperacao(comando.operationId(), DynamoKeys.AGENDAMENTO);
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
        execucao.put("status", texto(StatusExecucao.AGENDADA.name()));
        execucao.put("operationId", texto(comando.operationId()));
        execucao.put("sagaId", texto(comando.sagaId()));
        execucao.put("orcamentoId", texto(payload.orcamentoId()));
        execucao.put("versaoOrcamento", numero(payload.versao()));
        execucao.put("pagamentoId", texto(payload.pagamentoId()));
        execucao.put("agendadoEm", texto(agora.toString()));
        execucao.put("itens", itens);

        Map<String, AttributeValue> outbox = new HashMap<>(DynamoKeys.chaveOutbox(comando.operationId()));
        outbox.put("type", texto(TipoEventoExecucao.AGENDADA.codigo()));
        outbox.put("operationId", texto(comando.operationId()));
        outbox.put("sagaId", texto(comando.sagaId()));
        outbox.put("osId", texto(osId));
        outbox.put("causationId", texto(comando.messageId()));
        outbox.put("occurredAt", texto(agora.toString()));
        outbox.put("orcamentoId", texto(payload.orcamentoId()));
        outbox.put("versaoOrcamento", numero(payload.versao()));
        outbox.put("pagamentoId", texto(payload.pagamentoId()));
        outbox.put("published", AttributeValue.builder().bool(false).build());
        anexarRastreamento(outbox, rastreamento.capturar());

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
                        outbox.put("type", texto(TipoEventoExecucao.REJEITADA.codigo()));
                        outbox.put("motivo", texto(MotivoResultadoExecucao.ESTOQUE_INSUFICIENTE));
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

}
