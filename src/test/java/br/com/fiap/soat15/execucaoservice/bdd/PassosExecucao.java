package br.com.fiap.soat15.execucaoservice.bdd;

import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Então;
import io.cucumber.java.pt.Quando;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class PassosExecucao {
    private static final AtomicInteger SEQUENCIA = new AtomicInteger();
    private static final List<Map<String, Object>> EVENTOS = new CopyOnWriteArrayList<>();
    private static final Duration LIMITE = Duration.ofSeconds(30);
    private static final String ATRIBUTO_TRACEPARENT = "_traceparent";

    private final AmbienteBdd ambiente = AmbienteBdd.obter();
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String osId = "bdd-" + SEQUENCIA.incrementAndGet() + "-" + System.currentTimeMillis();
    private String traceId;

    @Dado("que a peça {string} tem {int} unidades em estoque")
    public void pecaComEstoque(String peca, int unidades) throws Exception {
        String corpo = "{\"tipo\":\"PECA\",\"itemId\":\"" + item(peca) + "\",\"nome\":\"" + peca
                + "\",\"precoUnitario\":10.00,\"ativo\":true,\"quantidadeEstoque\":" + unidades + "}";
        assertThat(chamar("POST", "/api/v1/catalogo", corpo).statusCode()).isEqualTo(204);
    }

    @Quando("o OS Service envia/enviou AgendarExecucao com {int} unidades de {string}")
    public void agendar(int unidades, String peca) {
        String comando = "{\"schemaVersion\":1,\"messageId\":\"" + UUID.randomUUID() + "\",\"operationId\":\"agendar-" + osId
                + "\",\"type\":\"AgendarExecucao\",\"sagaId\":\"saga-" + osId + "\",\"osId\":\"" + osId
                + "\",\"occurredAt\":\"2026-11-02T14:00:00Z\",\"payload\":{\"orcamentoId\":\"orc-" + osId
                + "\",\"versao\":1,\"pagamentoId\":\"pag-" + osId + "\",\"itens\":[{\"tipo\":\"PECA\",\"itemId\":\""
                + item(peca) + "\",\"quantidade\":" + unidades + "}]}}";
        enviarComando(comando);
    }

    @Quando("o OS Service envia CancelarExecucao")
    public void cancelar() {
        String comando = "{\"schemaVersion\":1,\"messageId\":\"" + UUID.randomUUID() + "\",\"operationId\":\"cancelar-" + osId
                + "\",\"type\":\"CancelarExecucao\",\"sagaId\":\"saga-" + osId + "\",\"osId\":\"" + osId
                + "\",\"occurredAt\":\"2026-11-02T15:00:00Z\"}";
        enviarComando(comando);
    }

    @Quando("o mecânico inicia a execução")
    public void iniciar() throws Exception {
        assertThat(chamar("POST", "/api/v1/execucoes/" + osId + "/inicio", null).statusCode()).isEqualTo(200);
    }

    @Dado("o OS Service está no trace {string}")
    public void noTrace(String traceId) {
        this.traceId = traceId;
    }

    @Quando("o mecânico inicia a execução no mesmo trace")
    public void iniciarNoMesmoTrace() throws Exception {
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(ambiente.urlApi() + "/api/v1/execucoes/" + osId + "/inicio"))
                .header("traceparent", traceparent()).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(http.send(requisicao, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Então("o evento {string} é publicado no mesmo trace")
    public void eventoNoMesmoTrace(String tipo) {
        Map<String, Object> evento = aguardarEvento(tipo);
        assertThat((String) evento.get(ATRIBUTO_TRACEPARENT)).startsWith("00-" + traceId + "-");
    }

    private String traceparent() {
        String span = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + span + "-01";
    }

    @Quando("o mecânico conclui a execução")
    public void concluir() throws Exception {
        assertThat(chamar("POST", "/api/v1/execucoes/" + osId + "/conclusao", null).statusCode()).isEqualTo(200);
    }

    @Então("a execução fica {string}")
    public void execucaoFica(String status) {
        await().atMost(LIMITE).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(statusDaExecucao()).isEqualTo(status));
    }

    @Então("a execução continua {string}")
    public void execucaoContinua(String status) throws Exception {
        assertThat(statusDaExecucao()).isEqualTo(status);
    }

    @Então("nenhuma execução é criada")
    public void nenhumaExecucao() throws Exception {
        assertThat(chamar("GET", "/api/v1/execucoes/" + osId, null).statusCode()).isEqualTo(404);
    }

    @Então("o estoque de {string} passa a ser {int}")
    public void estoquePassaASer(String peca, int esperado) {
        await().atMost(LIMITE).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(estoque(peca)).isEqualTo(esperado));
    }

    @Então("o estoque de {string} continua {int}")
    public void estoqueContinua(String peca, int esperado) throws Exception {
        assertThat(estoque(peca)).isEqualTo(esperado);
    }

    @Então("o evento {string} é publicado")
    public void eventoPublicado(String tipo) {
        aguardarEvento(tipo);
    }

    @Então("o evento {string} é publicado com o motivo {string}")
    @SuppressWarnings("unchecked")
    public void eventoPublicadoComMotivo(String tipo, String motivo) {
        Map<String, Object> evento = aguardarEvento(tipo);
        assertThat(((Map<String, Object>) evento.get("payload")).get("motivo")).isEqualTo(motivo);
    }

    private String item(String peca) {
        return peca + "-" + osId;
    }

    private void enviarComando(String corpo) {
        Map<String, MessageAttributeValue> atributos = traceId == null ? Map.of()
                : Map.of("traceparent", MessageAttributeValue.builder().dataType("String").stringValue(traceparent()).build());
        ambiente.sqs().sendMessage(b -> b.queueUrl(ambiente.urlComandos()).messageBody(corpo).messageAttributes(atributos));
    }

    private String statusDaExecucao() throws Exception {
        HttpResponse<String> resposta = chamar("GET", "/api/v1/execucoes/" + osId, null);
        if (resposta.statusCode() != 200) {
            return "HTTP " + resposta.statusCode();
        }
        return (String) mapper.readValue(resposta.body(), Map.class).get("status");
    }

    private int estoque(String peca) throws Exception {
        HttpResponse<String> resposta = chamar("GET", "/api/v1/catalogo/PECA/" + item(peca), null);
        assertThat(resposta.statusCode()).isEqualTo(200);
        return (Integer) mapper.readValue(resposta.body(), Map.class).get("quantidadeEstoque");
    }

    private HttpResponse<String> chamar(String metodo, String caminho, String corpo) throws Exception {
        HttpRequest.BodyPublisher conteudo = corpo == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo);
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(ambiente.urlApi() + caminho))
                .header("Content-Type", "application/json").method(metodo, conteudo).build();
        return http.send(requisicao, HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> aguardarEvento(String tipo) {
        return await().atMost(LIMITE).pollInterval(Duration.ofMillis(300)).until(() -> {
            lerFilaDeResultados();
            return procurarEvento(tipo);
        }, Objects::nonNull);
    }

    private Map<String, Object> procurarEvento(String tipo) {
        return EVENTOS.stream()
                .filter(evento -> osId.equals(evento.get("osId")) && tipo.equals(evento.get("type")))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private void lerFilaDeResultados() {
        var mensagens = ambiente.sqs().receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(ambiente.urlResultados()).maxNumberOfMessages(10).waitTimeSeconds(1)
                .messageAttributeNames("All").build()).messages();
        for (var mensagem : mensagens) {
            Map<String, Object> evento = new java.util.HashMap<>(mapper.readValue(mensagem.body(), Map.class));
            var traceparent = mensagem.messageAttributes().get("traceparent");
            if (traceparent != null) {
                evento.put(ATRIBUTO_TRACEPARENT, traceparent.stringValue());
            }
            EVENTOS.add(evento);
            ambiente.sqs().deleteMessage(b -> b.queueUrl(ambiente.urlResultados()).receiptHandle(mensagem.receiptHandle()));
        }
    }
}
