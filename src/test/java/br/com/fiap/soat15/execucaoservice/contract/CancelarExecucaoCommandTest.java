package br.com.fiap.soat15.execucaoservice.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancelarExecucaoCommandTest {
    private static final String UUID = "7b9c84b4-144f-4ad1-943e-c30bc8e2073b";

    private static CancelarExecucaoCommand comando(int schema, String messageId, String operationId, String type,
                                                   String sagaId, String osId, String occurredAt) {
        return new CancelarExecucaoCommand(schema, messageId, operationId, type, sagaId, osId, "causa", occurredAt);
    }

    @Test
    void aceitaEnvelopeValido() {
        CancelarExecucaoCommand valido = comando(1, UUID, "cancelar-1", "CancelarExecucao", "saga-1", "1", "2026-11-02T15:00:00Z");
        assertThat(valido.osId()).isEqualTo("1");
        assertThat(valido.operationId()).isEqualTo("cancelar-1");
    }

    @Test
    void rejeitaVersaoDeSchemaOuTipoErrados() {
        assertThatThrownBy(() -> comando(2, UUID, "op", "CancelarExecucao", "s", "1", "2026-11-02T15:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comando(1, UUID, "op", "AgendarExecucao", "s", "1", "2026-11-02T15:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejeitaCamposObrigatoriosAusentesOuEmBranco() {
        assertThatThrownBy(() -> comando(1, null, "op", "CancelarExecucao", "s", "1", "2026-11-02T15:00:00Z")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comando(1, UUID, " ", "CancelarExecucao", "s", "1", "2026-11-02T15:00:00Z")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comando(1, UUID, "op", "CancelarExecucao", null, "1", "2026-11-02T15:00:00Z")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comando(1, UUID, "op", "CancelarExecucao", "s", "", "2026-11-02T15:00:00Z")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comando(1, UUID, "op", "CancelarExecucao", "s", "1", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejeitaMessageIdQueNaoEUuidEDataInvalida() {
        assertThatThrownBy(() -> comando(1, "nao-e-uuid", "op", "CancelarExecucao", "s", "1", "2026-11-02T15:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> comando(1, UUID, "op", "CancelarExecucao", "s", "1", "ontem"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
