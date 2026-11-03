package br.com.fiap.soat15.execucaoservice.adapter.in.sqs;

import br.com.fiap.soat15.execucaoservice.application.ExecucaoJaAgendadaException;
import br.com.fiap.soat15.execucaoservice.application.ExecucaoService;
import br.com.fiap.soat15.execucaoservice.application.PecaIndisponivelException;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.contract.CancelarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.contract.TipoComandoExecucao;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "sqs.consumer.enabled", havingValue = "true")
public class AgendarExecucaoConsumer {
    private static final Logger log = LoggerFactory.getLogger(AgendarExecucaoConsumer.class);
    static final int MAXIMO_MENSAGENS_PADRAO = 10;
    private final SqsClient sqs;
    private final ExecucaoService service;
    private final String queueUrl;
    private final int maximoMensagens;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    public AgendarExecucaoConsumer(SqsClient sqs, ExecucaoService service,
                                   @Value("${sqs.execucao-comandos-url}") String queueUrl,
                                   @Value("${sqs.consumer.max-messages:10}") int maximoMensagens) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalArgumentException("SQS_EXECUCAO_COMANDOS_URL é obrigatória quando o consumidor está ativo");
        }
        if (maximoMensagens < 1 || maximoMensagens > 10) {
            throw new IllegalArgumentException("SQS_CONSUMER_MAX_MESSAGES deve estar entre 1 e 10");
        }
        this.sqs = sqs;
        this.service = service;
        this.queueUrl = queueUrl;
        this.maximoMensagens = maximoMensagens;
    }

    public AgendarExecucaoConsumer(SqsClient sqs, ExecucaoService service, String queueUrl) {
        this(sqs, service, queueUrl, MAXIMO_MENSAGENS_PADRAO);
    }

    @Scheduled(fixedDelayString = "${sqs.consumer.poll-delay-ms:5000}")
    public void consumir() {
        var mensagens = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                .maxNumberOfMessages(maximoMensagens).waitTimeSeconds(10).build()).messages();
        for (var mensagem : mensagens) {
            try {
                processar(mensagem);
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl)
                        .receiptHandle(mensagem.receiptHandle()).build());
            } catch (RuntimeException falha) {
                tratarFalha(mensagem, falha);
            }
        }
    }

    private void processar(Message mensagem) {
        var envelope = mapper.readValue(mensagem.body(), Map.class);
        TipoComandoExecucao tipo = TipoComandoExecucao.fromCodigo((String) envelope.get("type"));
        switch (tipo) {
            case CANCELAR -> service.cancelarExecucao(
                    mapper.readValue(mensagem.body(), CancelarExecucaoCommand.class));
            case AGENDAR -> {
                var comando = mapper.readValue(mensagem.body(), AgendarExecucaoCommand.class);
                try {
                    service.agendarExecucao(comando);
                } catch (PecaIndisponivelException rejeitada) {
                    log.info("Agendamento rejeitado por peça indisponível: {}", comando.operationId());
                }
            }
        }
    }

    private void tratarFalha(Message mensagem, RuntimeException falha) {
        if (permanente(falha)) {
            log.error("Mensagem SQS {} não pode ser processada ({}); será reentregue sem espera até a DLQ",
                    mensagem.messageId(), falha.getMessage());
            liberarParaNovaEntrega(mensagem);
        } else {
            log.warn("Falha ao processar mensagem SQS {}; será disponibilizada novamente após o tempo de visibilidade",
                    mensagem.messageId(), falha);
        }
    }

    static boolean permanente(RuntimeException falha) {
        return falha instanceof JacksonException
                || falha instanceof IllegalArgumentException
                || falha instanceof ExecucaoJaAgendadaException;
    }

    private void liberarParaNovaEntrega(Message mensagem) {
        try {
            sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(queueUrl)
                    .receiptHandle(mensagem.receiptHandle()).visibilityTimeout(0).build());
        } catch (RuntimeException erro) {
            log.warn("Não foi possível liberar a mensagem SQS {} para nova entrega imediata", mensagem.messageId(), erro);
        }
    }
}
