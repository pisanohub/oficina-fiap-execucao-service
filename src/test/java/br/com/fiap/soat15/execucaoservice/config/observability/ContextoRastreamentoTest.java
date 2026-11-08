package br.com.fiap.soat15.execucaoservice.config.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static br.com.fiap.soat15.execucaoservice.config.observability.RastreamentoDeTeste.TRACEPARENT;
import static org.assertj.core.api.Assertions.assertThat;

class ContextoRastreamentoTest {
    private final ContextoRastreamento contexto = new RastreamentoDeTeste().contexto();

    @Test
    void semTraceAtivoNaoHaContexto() {
        assertThat(contexto.capturar()).isEmpty();
    }

    @Test
    void restauraOContextoGuardadoEDepoisOLibera() {
        try (var escopo = contexto.restaurar(Map.of("traceparent", TRACEPARENT))) {
            assertThat(contexto.capturar()).containsEntry("traceparent", TRACEPARENT);
        }
        assertThat(contexto.capturar()).isEmpty();
    }

    @Test
    void ignoraContextoAusenteOuInvalido() {
        try (var escopo = contexto.restaurar(Map.of())) {
            assertThat(contexto.capturar()).isEmpty();
        }
        try (var escopo = contexto.restaurar(Map.of("traceparent", "lixo"))) {
            assertThat(contexto.capturar()).isEmpty();
        }
    }

    @Test
    void desligadoNaoFalhaENaoGeraContexto() {
        ContextoRastreamento desligado = ContextoRastreamento.desligado();
        try (var escopo = desligado.restaurar(Map.of("traceparent", TRACEPARENT))) {
            assertThat(desligado.capturar()).isEmpty();
        }
    }
}
