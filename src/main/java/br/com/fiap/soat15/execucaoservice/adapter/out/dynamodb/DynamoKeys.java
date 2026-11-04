package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

final class DynamoKeys {
    static final String PK = "pk";
    static final String SK = "sk";
    static final String EXECUCAO = "EXECUCAO";
    static final String DIAGNOSTICO = "DIAGNOSTICO";
    static final String CADASTRO = "CADASTRO";
    static final String RESULTADO = "RESULTADO";
    static final String AGENDAMENTO = "AGENDAMENTO";
    static final String CANCELAMENTO = "CANCELAMENTO";
    static final String PREFIXO_OS = "OS#";
    static final String PREFIXO_OUTBOX = "OUTBOX#";
    static final String PREFIXO_OPERACAO = "OPERACAO#";

    private DynamoKeys() {}

    static Map<String, AttributeValue> chaveExecucao(String osId) { return chave(PREFIXO_OS + osId, EXECUCAO); }
    static Map<String, AttributeValue> chaveDiagnostico(String osId) { return chave(PREFIXO_OS + osId, DIAGNOSTICO); }
    static Map<String, AttributeValue> chaveCatalogo(TipoItem tipo, String itemId) { return chave(tipo.name() + "#" + itemId, CADASTRO); }
    static Map<String, AttributeValue> chaveOperacao(String operationId, String tipo) { return chave(PREFIXO_OPERACAO + operationId, tipo); }
    static Map<String, AttributeValue> chaveOutbox(String operationId) { return chave(PREFIXO_OUTBOX + operationId, RESULTADO); }

    private static Map<String, AttributeValue> chave(String pk, String sk) {
        return Map.of(PK, AttributeValue.builder().s(pk).build(), SK, AttributeValue.builder().s(sk).build());
    }
}
