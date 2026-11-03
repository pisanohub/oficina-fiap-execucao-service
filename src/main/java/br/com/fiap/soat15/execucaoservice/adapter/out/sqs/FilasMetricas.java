package br.com.fiap.soat15.execucaoservice.adapter.out.sqs;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Acompanha a quantidade de mensagens nas filas e nas DLQs. Uma mensagem na DLQ indica uma operação
 * que não foi concluída e precisa de atenção.
 */
@Component
@ConditionalOnProperty(name = "sqs.metrics.enabled", havingValue = "true")
public class FilasMetricas {
    private static final Logger log = LoggerFactory.getLogger(FilasMetricas.class);
    private final SqsClient sqs;
    private final Map<String, String> urlPorFila = new LinkedHashMap<>();
    private final Map<String, AtomicLong> visiveis = new LinkedHashMap<>();
    private final Map<String, AtomicLong> emProcessamento = new LinkedHashMap<>();

    public FilasMetricas(SqsClient sqs, MeterRegistry registry,
                         @Value("${sqs.execucao-comandos-url:}") String comandos,
                         @Value("${sqs.execucao-comandos-dlq-url:}") String comandosDlq,
                         @Value("${sqs.execucao-resultados-url:}") String resultados,
                         @Value("${sqs.execucao-resultados-dlq-url:}") String resultadosDlq) {
        this.sqs = sqs;
        adicionar(registry, "comandos", comandos);
        adicionar(registry, "comandos-dlq", comandosDlq);
        adicionar(registry, "resultados", resultados);
        adicionar(registry, "resultados-dlq", resultadosDlq);
    }

    private void adicionar(MeterRegistry registry, String fila, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        urlPorFila.put(fila, url);
        AtomicLong prontas = new AtomicLong();
        AtomicLong emVoo = new AtomicLong();
        visiveis.put(fila, prontas);
        emProcessamento.put(fila, emVoo);
        Gauge.builder("oficina.execucao.fila.mensagens", prontas, AtomicLong::get)
                .description("Mensagens aguardando na fila").tag("fila", fila).tag("estado", "visiveis").register(registry);
        Gauge.builder("oficina.execucao.fila.mensagens", emVoo, AtomicLong::get)
                .description("Mensagens recebidas e ainda não confirmadas").tag("fila", fila).tag("estado", "em_processamento").register(registry);
    }

    @Scheduled(fixedDelayString = "${sqs.metrics.poll-delay-ms:60000}")
    public void atualizar() {
        urlPorFila.forEach((fila, url) -> {
            try {
                var atributos = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE).build()).attributes();
                visiveis.get(fila).set(Long.parseLong(atributos.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0")));
                emProcessamento.get(fila).set(Long.parseLong(
                        atributos.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0")));
            } catch (RuntimeException falha) {
                log.warn("Não foi possível consultar a fila {} para as métricas", fila, falha);
            }
        });
    }
}
