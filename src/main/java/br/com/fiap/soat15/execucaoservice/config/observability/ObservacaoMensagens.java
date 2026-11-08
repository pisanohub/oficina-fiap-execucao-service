package br.com.fiap.soat15.execucaoservice.config.observability;

import br.com.fiap.soat15.execucaoservice.application.OutboxEvent;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import io.micrometer.tracing.CurrentTraceContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.Map;

@Component
public class ObservacaoMensagens {
    static final String CONSUMO = "execucao.mensagens.consumo";
    static final String PUBLICACAO = "execucao.mensagens.publicacao";
    private static final String SISTEMA = "aws_sqs";

    private final ObservationRegistry registry;
    private final ContextoRastreamento contexto;

    public ObservacaoMensagens(ObservationRegistry registry, ContextoRastreamento contexto) {
        this.registry = registry;
        this.contexto = contexto;
    }

    public static ObservacaoMensagens desligada() {
        return new ObservacaoMensagens(ObservationRegistry.NOOP, ContextoRastreamento.desligado());
    }

    public void consumo(String fila, Message mensagem, Runnable processamento) {
        ReceiverContext<Message> receptor = new ReceiverContext<>(ObservacaoMensagens::atributo);
        receptor.setCarrier(mensagem);
        try {
            Observation.createNotStarted(CONSUMO, () -> receptor, registry)
                    .contextualName("process " + fila)
                    .lowCardinalityKeyValue("messaging.system", SISTEMA)
                    .lowCardinalityKeyValue("messaging.operation.name", "process")
                    .lowCardinalityKeyValue("messaging.destination.name", fila)
                    .highCardinalityKeyValue("messaging.message.id", mensagem.messageId())
                    .observe(processamento);
        } finally {
            limparCorrelacao();
        }
    }

    public void publicacao(String fila, OutboxEvent evento, Map<String, MessageAttributeValue> atributos,
                           Runnable envio) {
        SenderContext<Map<String, MessageAttributeValue>> emissor = new SenderContext<>(
                (carrier, chave, valor) -> carrier.put(chave,
                        MessageAttributeValue.builder().dataType("String").stringValue(valor).build()));
        emissor.setCarrier(atributos);
        try (CurrentTraceContext.Scope escopo = contexto.restaurar(evento.rastreamento())) {
            Observation.createNotStarted(PUBLICACAO, () -> emissor, registry)
                    .contextualName("send " + fila)
                    .lowCardinalityKeyValue("messaging.system", SISTEMA)
                    .lowCardinalityKeyValue("messaging.operation.name", "send")
                    .lowCardinalityKeyValue("messaging.destination.name", fila)
                    .lowCardinalityKeyValue("oficina.evento.tipo", evento.type())
                    .observe(() -> {
                        identificar(evento.osId(), evento.sagaId());
                        envio.run();
                    });
        } finally {
            limparCorrelacao();
        }
    }

    public void identificar(String osId, String sagaId) {
        Observation atual = registry.getCurrentObservation();
        if (osId != null) {
            MDC.put(CamposCorrelacao.OS_ID, osId);
            if (atual != null) {
                atual.highCardinalityKeyValue("oficina.os.id", osId);
            }
        }
        if (sagaId != null) {
            MDC.put(CamposCorrelacao.SAGA_ID, sagaId);
            if (atual != null) {
                atual.highCardinalityKeyValue("oficina.saga.id", sagaId);
            }
        }
    }

    public void registrarErro(Throwable erro) {
        Observation atual = registry.getCurrentObservation();
        if (atual != null) {
            atual.error(erro);
        }
    }

    private static String atributo(Message mensagem, String nome) {
        MessageAttributeValue valor = mensagem.messageAttributes().get(nome);
        return valor == null ? null : valor.stringValue();
    }

    private static void limparCorrelacao() {
        MDC.remove(CamposCorrelacao.OS_ID);
        MDC.remove(CamposCorrelacao.SAGA_ID);
    }
}
