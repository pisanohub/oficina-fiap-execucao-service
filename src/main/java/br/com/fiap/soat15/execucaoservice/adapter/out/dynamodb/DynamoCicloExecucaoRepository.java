package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.contract.CancelarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.domain.model.ExecucaoTrabalho;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.MotivoResultadoExecucao;
import br.com.fiap.soat15.execucaoservice.domain.model.PaginaExecucoes;
import br.com.fiap.soat15.execucaoservice.domain.model.StatusExecucao;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoEventoExecucao;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import br.com.fiap.soat15.execucaoservice.config.observability.ContextoRastreamento;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

final class DynamoCicloExecucaoRepository extends DynamoRepositorySupport {
    private final ContextoRastreamento rastreamento;

    DynamoCicloExecucaoRepository(DynamoDbClient dynamo, String tableName, ContextoRastreamento rastreamento) {
        super(dynamo, tableName);
        this.rastreamento = rastreamento;
    }

    public void cancelarExecucao(CancelarExecucaoCommand comando, Instant agora) {
        var chaveOperacao = DynamoKeys.chaveOperacao(comando.operationId(), DynamoKeys.CANCELAMENTO);
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
        boolean cancelar = StatusExecucao.AGENDADA.name().equals(status);
        boolean cancelada = StatusExecucao.CANCELADA.name().equals(status);
        if (!cancelar && !cancelada && !StatusExecucao.EM_EXECUCAO.name().equals(status) && !StatusExecucao.CONCLUIDA.name().equals(status)) {
            throw new IllegalStateException("Estado de execução desconhecido: " + status);
        }
        String tipo = cancelar || cancelada ? TipoEventoExecucao.CANCELADA.codigo() : TipoEventoExecucao.CANCELAMENTO_RECUSADO.codigo();
        var operacao = new HashMap<>(chaveOperacao);
        operacao.put("osId", texto(comando.osId()));
        operacao.put("sagaId", texto(comando.sagaId()));
        operacao.put("resultado", texto(tipo));
        Map<String, AttributeValue> evento = eventoDaExecucao(tipo, comando.operationId(),
                comando.messageId(), comando.osId(), agora, execucao);
        if (!cancelar && !cancelada) evento.put("motivo", texto(MotivoResultadoExecucao.ESTADO_NAO_PERMITE_CANCELAMENTO));
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
                    .expressionAttributeValues(Map.of(":novo", texto(StatusExecucao.CANCELADA.name()), ":atual", texto(status), ":agora", texto(agora.toString()))).build()).build());
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

    public List<ExecucaoTrabalho> listarExecucoes() {
        List<ExecucaoTrabalho> resultado = new ArrayList<>();
        Map<String, AttributeValue> cursor = Map.of();
        do {
            var request = ScanRequest.builder().tableName(tableName)
                    .filterExpression("begins_with(pk, :prefixo) AND sk = :sk")
                    .expressionAttributeValues(Map.of(":prefixo", texto(DynamoKeys.PREFIXO_OS), ":sk", texto(DynamoKeys.EXECUCAO)));
            if (cursor != null && !cursor.isEmpty()) request.exclusiveStartKey(cursor);
            var pagina = dynamo.scan(request.build());
            for (var item : pagina.items()) resultado.add(mapearExecucao(item));
            cursor = pagina.lastEvaluatedKey();
        } while (cursor != null && !cursor.isEmpty());
        return resultado;
    }

    public PaginaExecucoes listarExecucoes(int limite, String pagina) {
        Map<String, AttributeValue> chave = decodificarPagina(pagina);
        List<Map<String, AttributeValue>> itens = new ArrayList<>();
        do {
            var request = ScanRequest.builder().tableName(tableName)
                    .filterExpression("begins_with(pk, :prefixo) AND sk = :sk")
                    .expressionAttributeValues(Map.of(":prefixo", texto(DynamoKeys.PREFIXO_OS), ":sk", texto(DynamoKeys.EXECUCAO)));
            if (!chave.isEmpty()) request.exclusiveStartKey(chave);
            var resposta = dynamo.scan(request.build());
            itens.addAll(resposta.items());
            chave = resposta.lastEvaluatedKey() == null ? Map.of() : resposta.lastEvaluatedKey();
        } while (itens.size() < limite && !chave.isEmpty());
        String proximo = chave.isEmpty() ? null : codificarPagina(chave);
        if (itens.size() > limite) {
            // O Scan seguinte recomeça logo depois do último item devolvido.
            proximo = codificarPagina(chaveDoItem(itens.get(limite - 1)));
            itens = itens.subList(0, limite);
        }
        return new PaginaExecucoes(itens.stream().map(this::mapearExecucao).toList(), proximo);
    }

    private Map<String, AttributeValue> chaveDoItem(Map<String, AttributeValue> item) {
        return Map.of(DynamoKeys.PK, item.get(DynamoKeys.PK), DynamoKeys.SK, item.get(DynamoKeys.SK));
    }

    private String codificarPagina(Map<String, AttributeValue> chave) {
        String valor = chave.get(DynamoKeys.PK).s() + "\n" + chave.get(DynamoKeys.SK).s();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(valor.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, AttributeValue> decodificarPagina(String pagina) {
        if (pagina == null || pagina.isBlank()) {
            return Map.of();
        }
        try {
            String[] partes = new String(Base64.getUrlDecoder().decode(pagina), StandardCharsets.UTF_8).split("\n", -1);
            if (partes.length != 2 || !partes[0].startsWith(DynamoKeys.PREFIXO_OS) || !DynamoKeys.EXECUCAO.equals(partes[1])) {
                throw new IllegalArgumentException("pagina inválida");
            }
            return Map.of(DynamoKeys.PK, texto(partes[0]), DynamoKeys.SK, texto(partes[1]));
        } catch (IllegalArgumentException invalido) {
            throw new IllegalArgumentException("pagina inválida");
        }
    }

    public Optional<ExecucaoTrabalho> buscarExecucao(String osId) {
        var item = buscar(chaveExecucao(osId));
        return item.isEmpty() ? Optional.empty() : Optional.of(mapearExecucao(item));
    }

    public ExecucaoTrabalho iniciarExecucao(String osId, Instant agora) {
        var chave = chaveExecucao(osId);
        var item = buscar(chave);
        if (item.isEmpty()) throw new NoSuchElementException("Execução não encontrada: " + osId);
        if (StatusExecucao.EM_EXECUCAO.name().equals(item.get("status").s())) return mapearExecucao(item);
        if (!StatusExecucao.AGENDADA.name().equals(item.get("status").s())) throw new IllegalStateException("Execução não pode ser iniciada no estado atual");
        String operacao = "iniciar-execucao-" + UUID.nameUUIDFromBytes(osId.getBytes(StandardCharsets.UTF_8));
        Map<String, AttributeValue> evento = eventoDaExecucao(TipoEventoExecucao.INICIADA.codigo(),
                operacao, operacao, osId, agora, item);
        var atualizar = TransactWriteItem.builder().update(Update.builder().tableName(tableName).key(chave)
                .updateExpression("SET #status = :iniciada, iniciadaEm = :agora")
                .conditionExpression("#status = :agendada")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":iniciada", texto(StatusExecucao.EM_EXECUCAO.name()), ":agendada", texto(StatusExecucao.AGENDADA.name()), ":agora", texto(agora.toString())))
                .build()).build();
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(atualizar, putSeAusente(evento)).build());
        } catch (TransactionCanceledException falha) {
            var atual = buscar(chave);
            if (!atual.isEmpty() && StatusExecucao.EM_EXECUCAO.name().equals(atual.get("status").s())) return mapearExecucao(atual);
            throw falha;
        }
        var atual = new HashMap<>(item);
        atual.put("status", texto(StatusExecucao.EM_EXECUCAO.name()));
        atual.put("iniciadaEm", texto(agora.toString()));
        return mapearExecucao(atual);
    }

    public ExecucaoTrabalho concluirExecucao(String osId, Instant agora) {
        var chave = chaveExecucao(osId);
        var item = buscar(chave);
        if (item.isEmpty()) throw new NoSuchElementException("Execução não encontrada: " + osId);
        if (StatusExecucao.CONCLUIDA.name().equals(item.get("status").s())) return mapearExecucao(item);
        if (!StatusExecucao.EM_EXECUCAO.name().equals(item.get("status").s())) throw new IllegalStateException("Execução precisa estar em execução para ser concluída");
        String operacao = "concluir-execucao-" + UUID.nameUUIDFromBytes(osId.getBytes(StandardCharsets.UTF_8));
        Map<String, AttributeValue> evento = eventoDaExecucao(TipoEventoExecucao.CONCLUIDA.codigo(),
                operacao, operacao, osId, agora, item);
        evento.put("iniciadaEm", item.get("iniciadaEm"));
        var atualizar = TransactWriteItem.builder().update(Update.builder().tableName(tableName).key(chave)
                .updateExpression("SET #status = :concluida, concluidaEm = :agora")
                .conditionExpression("#status = :iniciada")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":concluida", texto(StatusExecucao.CONCLUIDA.name()), ":iniciada", texto(StatusExecucao.EM_EXECUCAO.name()), ":agora", texto(agora.toString())))
                .build()).build();
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(atualizar, putSeAusente(evento)).build());
        } catch (TransactionCanceledException falha) {
            var atual = buscar(chave);
            if (!atual.isEmpty() && StatusExecucao.CONCLUIDA.name().equals(atual.get("status").s())) return mapearExecucao(atual);
            if (!atual.isEmpty() && !StatusExecucao.EM_EXECUCAO.name().equals(atual.get("status").s())) throw new IllegalStateException("Execução não pode ser concluída no estado atual", falha);
            throw falha;
        }
        var atual = new HashMap<>(item);
        atual.put("status", texto(StatusExecucao.CONCLUIDA.name()));
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

    private Map<String, AttributeValue> eventoDaExecucao(String tipo, String operationId,
            String causationId, String osId, Instant agora, Map<String, AttributeValue> execucao) {
        Map<String, AttributeValue> evento = new HashMap<>(DynamoKeys.chaveOutbox(operationId));
        evento.put("type", texto(tipo));
        evento.put("operationId", texto(operationId));
        evento.put("sagaId", execucao.get("sagaId"));
        evento.put("osId", texto(osId));
        evento.put("causationId", texto(causationId));
        evento.put("occurredAt", texto(agora.toString()));
        evento.put("orcamentoId", execucao.get("orcamentoId"));
        evento.put("versaoOrcamento", execucao.get("versaoOrcamento"));
        evento.put("pagamentoId", execucao.get("pagamentoId"));
        evento.put("published", AttributeValue.builder().bool(false).build());
        anexarRastreamento(evento, rastreamento.capturar());
        return evento;
    }

}
