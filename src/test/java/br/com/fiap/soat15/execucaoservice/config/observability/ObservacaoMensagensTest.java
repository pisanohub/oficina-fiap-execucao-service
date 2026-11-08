package br.com.fiap.soat15.execucaoservice.config.observability;

import br.com.fiap.soat15.execucaoservice.application.OutboxEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static br.com.fiap.soat15.execucaoservice.config.observability.RastreamentoDeTeste.SPAN_PAI;
import static br.com.fiap.soat15.execucaoservice.config.observability.RastreamentoDeTeste.TRACEPARENT;
import static br.com.fiap.soat15.execucaoservice.config.observability.RastreamentoDeTeste.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;

class ObservacaoMensagensTest {
    private final RastreamentoDeTeste rastreamento = new RastreamentoDeTeste();
    private final ObservacaoMensagens observacao = rastreamento.observacao();

    private static Message mensagemComTrace() {
        return Message.builder().messageId("msg-1").body("{}")
                .messageAttributes(Map.of("traceparent", MessageAttributeValue.builder()
                        .dataType("String").stringValue(TRACEPARENT).build())).build();
    }

    @Test
    void consumoContinuaOTraceDaMensagemEIdentificaAOs() {
        List<String> mdcDuranteOProcessamento = new ArrayList<>();

        observacao.consumo("execucao-comandos", mensagemComTrace(), () -> {
            observacao.identificar("os-1", "saga-1");
            mdcDuranteOProcessamento.add(MDC.get(CamposCorrelacao.OS_ID));
            mdcDuranteOProcessamento.add(MDC.get(CamposCorrelacao.SAGA_ID));
        });

        var span = rastreamento.unicoSpan();
        assertThat(span.getName()).isEqualTo("process execucao-comandos");
        assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(span.getParentSpanId()).isEqualTo(SPAN_PAI);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.system"))).isEqualTo("aws_sqs");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.message.id"))).isEqualTo("msg-1");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("oficina.os.id"))).isEqualTo("os-1");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("oficina.saga.id"))).isEqualTo("saga-1");
        assertThat(mdcDuranteOProcessamento).containsExactly("os-1", "saga-1");
        assertThat(MDC.get(CamposCorrelacao.OS_ID)).isNull();
        assertThat(MDC.get(CamposCorrelacao.SAGA_ID)).isNull();
    }

    @Test
    void erroRegistradoMarcaOSpan() {
        observacao.consumo("execucao-comandos", mensagemComTrace(),
                () -> observacao.registrarErro(new IllegalArgumentException("inválida")));

        assertThat(rastreamento.unicoSpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void publicacaoContinuaOTraceGuardadoNaOutboxEOEnviaNosAtributos() {
        var evento = new OutboxEvent("op-1", "ExecucaoAgendada", "saga-1", "os-1", "c", Instant.EPOCH, "orc", 1, "pag",
                null, null, null, Map.of("traceparent", TRACEPARENT));
        Map<String, MessageAttributeValue> atributos = new HashMap<>();
        List<String> osNoLog = new ArrayList<>();

        observacao.publicacao("execucao-resultados", evento, atributos, () -> osNoLog.add(MDC.get(CamposCorrelacao.OS_ID)));

        var span = rastreamento.unicoSpan();
        assertThat(span.getName()).isEqualTo("send execucao-resultados");
        assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(span.getParentSpanId()).isEqualTo(SPAN_PAI);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("oficina.evento.tipo"))).isEqualTo("ExecucaoAgendada");
        assertThat(atributos.get("traceparent").stringValue())
                .isEqualTo("00-" + TRACE_ID + "-" + span.getSpanId() + "-01");
        assertThat(osNoLog).containsExactly("os-1");
        assertThat(MDC.get(CamposCorrelacao.OS_ID)).isNull();
    }

    @Test
    void publicacaoSemContextoGuardadoComecaUmTraceNovo() {
        var evento = new OutboxEvent("op-1", "ExecucaoAgendada", "saga-1", "os-1", "c", Instant.EPOCH, "orc", 1, "pag");
        Map<String, MessageAttributeValue> atributos = new HashMap<>();

        observacao.publicacao("execucao-resultados", evento, atributos, () -> { });

        assertThat(rastreamento.unicoSpan().getTraceId()).isNotEqualTo(TRACE_ID);
        assertThat(atributos).containsKey("traceparent");
    }

    @Test
    void desligadaExecutaSemGerarAtributos() {
        Map<String, MessageAttributeValue> atributos = new HashMap<>();
        List<String> executou = new ArrayList<>();
        var desligada = ObservacaoMensagens.desligada();

        desligada.consumo("execucao-comandos", mensagemComTrace(), () -> executou.add("consumo"));
        desligada.publicacao("execucao-resultados", new OutboxEvent("op", "ExecucaoAgendada", "s", "o", "c",
                Instant.EPOCH, "orc", 1, "p"), atributos, () -> executou.add("publicacao"));
        desligada.registrarErro(new IllegalStateException());

        assertThat(executou).containsExactly("consumo", "publicacao");
        assertThat(atributos).isEmpty();
    }
}
