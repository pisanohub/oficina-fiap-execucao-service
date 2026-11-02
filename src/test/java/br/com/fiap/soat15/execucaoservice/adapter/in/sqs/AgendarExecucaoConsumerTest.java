package br.com.fiap.soat15.execucaoservice.adapter.in.sqs;

import br.com.fiap.soat15.execucaoservice.application.ExecucaoService;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgendarExecucaoConsumerTest {
    private final SqsClient sqs = mock(SqsClient.class);
    private final ExecucaoService service = mock(ExecucaoService.class);
    private final AgendarExecucaoConsumer consumer = new AgendarExecucaoConsumer(sqs, service, "queue");

    @Test
    void processaEConfirmaMensagemValida() {
        var body = """
            {"schemaVersion":1,"messageId":"02539ee2-c754-4bb7-b178-4b328de9dbd5",
             "operationId":"op-1","type":"AgendarExecucao","sagaId":"saga-1","osId":"os-1",
             "causationId":"causa-1","occurredAt":"2026-11-02T14:00:00Z",
             "payload":{"orcamentoId":"orc-1","versao":1,"pagamentoId":"pag-1",
                        "itens":[{"tipo":"PECA","itemId":"filtro","quantidade":1}]}}
            """;
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().messageId("id").receiptHandle("receipt").body(body).build()).build());
        consumer.consumir();
        verify(service).agendarExecucao(any());
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void naoConfirmaMensagemInvalida() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().messageId("id").receiptHandle("receipt").body("{}").build()).build());
        consumer.consumir();
        verifyNoInteractions(service);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }
}
