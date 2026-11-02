package br.com.fiap.soat15.execucaoservice.adapter.in.sqs;

import br.com.fiap.soat15.execucaoservice.application.ExecucaoService;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "sqs.consumer.enabled", havingValue = "true")
public class AgendarExecucaoConsumer {
    private static final Logger log = LoggerFactory.getLogger(AgendarExecucaoConsumer.class);
    private final SqsClient sqs;
    private final ExecucaoService service;
    private final String queueUrl;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AgendarExecucaoConsumer(SqsClient sqs, ExecucaoService service,
                                   @Value("${sqs.execucao-comandos-url}") String queueUrl) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalArgumentException("SQS_EXECUCAO_COMANDOS_URL é obrigatória quando o consumidor está ativo");
        }
        this.sqs = sqs;
        this.service = service;
        this.queueUrl = queueUrl;
    }

    @Scheduled(fixedDelayString = "${sqs.consumer.poll-delay-ms:5000}")
    public void consumir() {
        var mensagens = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                .maxNumberOfMessages(1).waitTimeSeconds(10).build()).messages();
        for (var mensagem : mensagens) {
            try {
                var comando = mapper.readValue(mensagem.body(), AgendarExecucaoCommand.class);
                service.agendarExecucao(comando);
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl)
                        .receiptHandle(mensagem.receiptHandle()).build());
            } catch (RuntimeException falha) {
                log.warn("Falha ao processar mensagem SQS {}; será disponibilizada novamente", mensagem.messageId(), falha);
            }
        }
    }
}
