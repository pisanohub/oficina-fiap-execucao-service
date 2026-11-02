package br.com.fiap.soat15.execucaoservice.contract;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgendarExecucaoCommandTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void leContratoV1ComItensAprovados() {
        AgendarExecucaoCommand command = mapper.readValue("""
                {
                  "schemaVersion": 1,
                  "messageId": "02539ee2-c754-4bb7-b178-4b328de9dbd5",
                  "operationId": "agendar-os-123-orcamento-v2",
                  "type": "AgendarExecucao",
                  "sagaId": "saga-123",
                  "osId": "123",
                  "causationId": "evento-pagamento-123",
                  "occurredAt": "2026-11-02T14:00:00Z",
                  "payload": {
                    "orcamentoId": "orc-123", "versao": 2, "pagamentoId": "pag-123",
                    "itens": [{"tipo": "PECA", "itemId": "filtro", "quantidade": 1}]
                  }
                }
                """, AgendarExecucaoCommand.class);

        assertThat(command.schemaVersion()).isEqualTo(1);
        assertThat(command.payload().itens()).singleElement()
                .satisfies(item -> assertThat(item.itemId()).isEqualTo("filtro"));
    }

    @Test
    void rejeitaVersaoOuTipoDiferente() {
        String json = """
                {"schemaVersion":2,"messageId":"02539ee2-c754-4bb7-b178-4b328de9dbd5",
                 "operationId":"op-1","type":"AgendarExecucao","sagaId":"saga-1","osId":"1",
                 "occurredAt":"2026-11-02T14:00:00Z","payload":{"orcamentoId":"orc-1",
                 "versao":1,"pagamentoId":"pag-1","itens":[{"tipo":"PECA","itemId":"filtro","quantidade":1}]}}
                """;
        assertThatThrownBy(() -> mapper.readValue(json, AgendarExecucaoCommand.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
