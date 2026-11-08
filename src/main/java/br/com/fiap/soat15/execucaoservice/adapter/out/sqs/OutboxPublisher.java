package br.com.fiap.soat15.execucaoservice.adapter.out.sqs;

import br.com.fiap.soat15.execucaoservice.application.ExecucaoMetricas;
import br.com.fiap.soat15.execucaoservice.application.OutboxEvent;
import br.com.fiap.soat15.execucaoservice.application.OutboxRepository;
import br.com.fiap.soat15.execucaoservice.config.observability.ObservacaoMensagens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final String FILA = "execucao-resultados";
    private final OutboxRepository repository;
    private final ExecucaoMetricas metricas;
    private final ObservacaoMensagens observacao;
    private final SqsClient sqs;
    private final JsonMapper mapper;
    private final Clock clock;
    private final String queueUrl;
    private final String dono = UUID.randomUUID().toString();

    public OutboxPublisher(OutboxRepository repository, ExecucaoMetricas metricas, ObservacaoMensagens observacao,
                           SqsClient sqs, Clock clock, @Value("${sqs.execucao-resultados-url}") String queueUrl) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalArgumentException("SQS_EXECUCAO_RESULTADOS_URL é obrigatória quando a outbox está ativa");
        }
        this.repository = repository;
        this.metricas = metricas;
        this.observacao = observacao;
        this.sqs = sqs;
        this.clock = clock;
        this.queueUrl = queueUrl;
        this.mapper = JsonMapper.builder().build();
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.poll-delay-ms:5000}")
    public void publicarPendentes() {
        List<OutboxEvent> pendentes = repository.pendentes(20);
        metricas.atrasoDaOutbox(atrasoDoMaisAntigo(pendentes));
        for (OutboxEvent evento : pendentes) {
            if (!repository.reservar(evento, dono, clock.instant(), clock.instant().plus(LEASE))) {
                continue;
            }
            publicar(evento);
        }
    }

    private void publicar(OutboxEvent evento) {
        Map<String, MessageAttributeValue> atributos = new HashMap<>();
        observacao.publicacao(FILA, evento, atributos, () -> {
            try {
                var resposta = sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl)
                        .messageBody(mapper.writeValueAsString(evento.mensagem()))
                        .messageAttributes(atributos).build());
                if (resposta.messageId() == null) {
                    throw new IllegalStateException("SQS não confirmou o envio de " + evento.operationId());
                }
                repository.marcarPublicado(evento, dono, clock.instant());
                metricas.eventoPublicado(evento.type());
            } catch (RuntimeException falha) {
                observacao.registrarErro(falha);
                metricas.falhaPublicacao();
                log.warn("Falha ao publicar resultado da operação {}; será tentado novamente após o lease",
                        evento.operationId(), falha);
            }
        });
    }

    private Duration atrasoDoMaisAntigo(List<OutboxEvent> pendentes) {
        Instant agora = clock.instant();
        return pendentes.stream().map(evento -> Duration.between(evento.occurredAt(), agora))
                .max(Duration::compareTo).orElse(Duration.ZERO);
    }
}
