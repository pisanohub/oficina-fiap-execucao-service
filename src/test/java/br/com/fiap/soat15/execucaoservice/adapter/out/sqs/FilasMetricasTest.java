package br.com.fiap.soat15.execucaoservice.adapter.out.sqs;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FilasMetricasTest {
    private final SqsClient sqs = mock(SqsClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double valor(String fila, String estado) {
        return registry.get("oficina.execucao.fila.mensagens").tag("fila", fila).tag("estado", estado).gauge().value();
    }

    @Test
    void registraSomenteAsFilasConfiguradasEAtualizaOsValores() {
        FilasMetricas metricas = new FilasMetricas(sqs, registry, "http://q/comandos", "http://q/comandos-dlq", "", "");
        when(sqs.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenAnswer(chamada -> {
            String url = chamada.<GetQueueAttributesRequest>getArgument(0).queueUrl();
            return GetQueueAttributesResponse.builder().attributes(url.endsWith("dlq")
                    ? Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "4",
                             QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "1")
                    : Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "7",
                             QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "2")).build();
        });

        metricas.atualizar();

        assertThat(valor("comandos", "visiveis")).isEqualTo(7);
        assertThat(valor("comandos", "em_processamento")).isEqualTo(2);
        assertThat(valor("comandos-dlq", "visiveis")).isEqualTo(4);
        assertThat(valor("comandos-dlq", "em_processamento")).isEqualTo(1);
        assertThat(registry.find("oficina.execucao.fila.mensagens").tag("fila", "resultados").gauge()).isNull();
        assertThat(registry.find("oficina.execucao.fila.mensagens").gauges()).hasSize(4);
    }

    @Test
    void falhaNaConsultaMantemOUltimoValorENaoPropagaOErro() {
        FilasMetricas metricas = new FilasMetricas(sqs, registry, "http://q/comandos", "", "", "");
        when(sqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "3")).build())
                .thenThrow(new RuntimeException("sqs fora"));

        metricas.atualizar();
        metricas.atualizar();

        assertThat(valor("comandos", "visiveis")).isEqualTo(3);
        assertThat(valor("comandos", "em_processamento")).isZero();
    }

    @Test
    void semNenhumaUrlNaoRegistraMedidores() {
        new FilasMetricas(sqs, registry, "", " ", null, "").atualizar();
        assertThat(registry.getMeters()).isEmpty();
    }
}
