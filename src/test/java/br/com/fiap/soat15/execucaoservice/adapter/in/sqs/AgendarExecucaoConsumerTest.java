package br.com.fiap.soat15.execucaoservice.adapter.in.sqs;

import br.com.fiap.soat15.execucaoservice.application.ExecucaoJaAgendadaException;
import br.com.fiap.soat15.execucaoservice.application.ExecucaoMetricas;
import br.com.fiap.soat15.execucaoservice.application.ExecucaoService;
import br.com.fiap.soat15.execucaoservice.application.PecaIndisponivelException;
import br.com.fiap.soat15.execucaoservice.config.observability.CamposCorrelacao;
import br.com.fiap.soat15.execucaoservice.config.observability.ObservacaoMensagens;
import br.com.fiap.soat15.execucaoservice.config.observability.RastreamentoDeTeste;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgendarExecucaoConsumerTest {
    private static final String AGENDAR = """
            {"schemaVersion":1,"messageId":"02539ee2-c754-4bb7-b178-4b328de9dbd5",
             "operationId":"op-1","type":"AgendarExecucao","sagaId":"saga-1","osId":"os-1",
             "causationId":"causa-1","occurredAt":"2026-11-02T14:00:00Z",
             "payload":{"orcamentoId":"orc-1","versao":1,"pagamentoId":"pag-1",
                        "itens":[{"tipo":"PECA","itemId":"filtro","quantidade":1}]}}
            """;
    private static final String CANCELAR = """
            {"schemaVersion":1,"messageId":"7b9c84b4-144f-4ad1-943e-c30bc8e2073b","operationId":"cancelar-1",
             "type":"CancelarExecucao","sagaId":"saga-1","osId":"os-1","occurredAt":"2026-11-02T15:00:00Z"}
            """;

    private final SqsClient sqs = mock(SqsClient.class);
    private final ExecucaoService service = mock(ExecucaoService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ExecucaoMetricas metricas = new ExecucaoMetricas(registry);
    private final AgendarExecucaoConsumer consumer = novoConsumidor("queue", 10);

    private AgendarExecucaoConsumer novoConsumidor(String fila, int maximo) {
        return new AgendarExecucaoConsumer(sqs, service, metricas, ObservacaoMensagens.desligada(), fila, maximo);
    }

    private static Message mensagem(String id, String body) {
        return Message.builder().messageId(id).receiptHandle("receipt-" + id).body(body).build();
    }

    private void recebe(Message... mensagens) {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(mensagens).build());
    }

    @Test
    void processaEConfirmaMensagemValida() {
        recebe(mensagem("id", AGENDAR));
        consumer.consumir();
        verify(service).agendarExecucao(any());
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    @Test
    void naoConfirmaMensagemInvalidaELiberaParaNovaEntregaImediata() {
        recebe(mensagem("id", "{}"));
        consumer.consumir();
        verifyNoInteractions(service);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        ArgumentCaptor<ChangeMessageVisibilityRequest> captor = ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqs).changeMessageVisibility(captor.capture());
        assertThat(captor.getValue().visibilityTimeout()).isZero();
        assertThat(captor.getValue().receiptHandle()).isEqualTo("receipt-id");
        assertThat(captor.getValue().queueUrl()).isEqualTo("queue");
    }

    @Test
    void textoQueNaoEJsonEFalhaPermanente() {
        recebe(mensagem("id", "nao-e-json"));
        consumer.consumir();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    @Test
    void erroDeNegocioPermanenteDoServicoLiberaParaNovaEntregaImediata() {
        recebe(mensagem("a", AGENDAR));
        doThrow(new IllegalArgumentException("operationId reutilizado com dados diferentes")).when(service).agendarExecucao(any());
        consumer.consumir();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    @Test
    void execucaoJaAgendadaTambemEFalhaPermanente() {
        recebe(mensagem("a", AGENDAR));
        doThrow(new ExecucaoJaAgendadaException("os-1", null)).when(service).agendarExecucao(any());
        consumer.consumir();
        verify(sqs).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    @Test
    void falhaTransitoriaMantemOTempoDeVisibilidadeDaFila() {
        recebe(mensagem("c", CANCELAR));
        doThrow(new NoSuchElementException("Execução não encontrada: os-1")).when(service).cancelarExecucao(any());
        consumer.consumir();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    @Test
    void pecaIndisponivelEResultadoDeNegocioEConfirmaAMensagem() {
        recebe(mensagem("a", AGENDAR));
        doThrow(new PecaIndisponivelException("filtro", null)).when(service).agendarExecucao(any());
        consumer.consumir();
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    @Test
    void falhaEmUmaMensagemNaoImpedeAsOutrasDoLote() {
        recebe(mensagem("ruim", "{}"), mensagem("boa", AGENDAR), mensagem("cancela", CANCELAR));
        consumer.consumir();
        verify(service).agendarExecucao(any());
        verify(service).cancelarExecucao(any());
        ArgumentCaptor<DeleteMessageRequest> apagadas = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs, times(2)).deleteMessage(apagadas.capture());
        assertThat(apagadas.getAllValues()).extracting(DeleteMessageRequest::receiptHandle)
                .containsExactly("receipt-boa", "receipt-cancela");
    }

    @Test
    void falhaAoLiberarAMensagemNaoInterrompeOConsumo() {
        recebe(mensagem("ruim", "{}"), mensagem("boa", AGENDAR));
        when(sqs.changeMessageVisibility(any(ChangeMessageVisibilityRequest.class))).thenThrow(new RuntimeException("sqs fora"));
        consumer.consumir();
        verify(service).agendarExecucao(any());
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void pedeAoSqsAQuantidadeConfiguradaDeMensagens() {
        recebe();
        novoConsumidor("queue", 3).consumir();
        consumer.consumir();
        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqs, times(2)).receiveMessage(captor.capture());
        assertThat(captor.getAllValues().get(0).maxNumberOfMessages()).isEqualTo(3);
        assertThat(captor.getAllValues().get(1).maxNumberOfMessages()).isEqualTo(10);
    }

    @Test
    void rejeitaConfiguracaoInvalida() {
        assertThatThrownBy(() -> novoConsumidor(" ", 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> novoConsumidor("queue", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> novoConsumidor("queue", 11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contaComandosProcessadosPorTipo() {
        recebe(mensagem("a", AGENDAR), mensagem("c", CANCELAR));
        consumer.consumir();
        assertThat(registry.counter("oficina.execucao.comandos.processados", "tipo", "AgendarExecucao").count()).isEqualTo(1);
        assertThat(registry.counter("oficina.execucao.comandos.processados", "tipo", "CancelarExecucao").count()).isEqualTo(1);
        assertThat(registry.get("oficina.execucao.comandos.duracao").tag("tipo", "AgendarExecucao").timer().count()).isEqualTo(1);
    }

    @Test
    void pecaIndisponivelContaComoComandoProcessado() {
        recebe(mensagem("a", AGENDAR));
        doThrow(new PecaIndisponivelException("filtro", null)).when(service).agendarExecucao(any());
        consumer.consumir();
        assertThat(registry.counter("oficina.execucao.comandos.processados", "tipo", "AgendarExecucao").count()).isEqualTo(1);
        assertThat(registry.find("oficina.execucao.comandos.falhas").counter()).isNull();
    }

    @Test
    void contaFalhasPermanentesETransitoriasComOTipoDoComando() {
        recebe(mensagem("a", AGENDAR), mensagem("c", CANCELAR), mensagem("ruim", "nao-e-json"));
        doThrow(new IllegalArgumentException("incoerente")).when(service).agendarExecucao(any());
        doThrow(new NoSuchElementException("sem execução")).when(service).cancelarExecucao(any());
        consumer.consumir();
        assertThat(registry.counter("oficina.execucao.comandos.falhas", "tipo", "AgendarExecucao", "natureza", "permanente").count()).isEqualTo(1);
        assertThat(registry.counter("oficina.execucao.comandos.falhas", "tipo", "CancelarExecucao", "natureza", "transitoria").count()).isEqualTo(1);
        assertThat(registry.counter("oficina.execucao.comandos.falhas", "tipo", "desconhecido", "natureza", "permanente").count()).isEqualTo(1);
        assertThat(registry.find("oficina.execucao.comandos.processados").counter()).isNull();
    }

    @Test
    void continuaOTraceRecebidoNosAtributosDaMensagem() {
        var rastreamento = new RastreamentoDeTeste();
        var consumidor = new AgendarExecucaoConsumer(sqs, service, metricas, rastreamento.observacao(), "queue", 10);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().messageId("a").receiptHandle("r").body(AGENDAR)
                        .messageAttributes(java.util.Map.of("traceparent", MessageAttributeValue.builder()
                                .dataType("String").stringValue(RastreamentoDeTeste.TRACEPARENT).build())).build()).build());
        java.util.List<String> traceDuranteOProcessamento = new java.util.ArrayList<>();
        java.util.List<String> osIdNoLog = new java.util.ArrayList<>();
        doAnswer(chamada -> {
            traceDuranteOProcessamento.add(rastreamento.contexto().capturar().get("traceparent"));
            osIdNoLog.add(org.slf4j.MDC.get(CamposCorrelacao.OS_ID));
            return null;
        }).when(service).agendarExecucao(any());

        consumidor.consumir();

        assertThat(traceDuranteOProcessamento).singleElement().asString().contains(RastreamentoDeTeste.TRACE_ID);
        assertThat(osIdNoLog).containsExactly("os-1");
        assertThat(rastreamento.unicoSpan().getName()).isEqualTo("process execucao-comandos");
        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqs).receiveMessage(captor.capture());
        assertThat(captor.getValue().messageAttributeNames()).containsExactly("All");
    }
}
