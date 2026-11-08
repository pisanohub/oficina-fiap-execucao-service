package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.config.observability.ContextoRastreamento;
import br.com.fiap.soat15.execucaoservice.domain.model.PaginaExecucoes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoExecucaoPaginacaoTest {
    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final DynamoExecucaoRepository repository = new DynamoExecucaoRepository(dynamo, "execucao_service",
            ContextoRastreamento.desligado());

    private static AttributeValue s(String valor) { return AttributeValue.builder().s(valor).build(); }

    private static Map<String, AttributeValue> execucao(String osId) {
        AttributeValue itens = AttributeValue.builder().l(AttributeValue.builder().m(Map.of(
                "tipo", s("PECA"), "itemId", s("filtro"), "quantidade", AttributeValue.builder().n("1").build())).build()).build();
        return Map.of("pk", s("OS#" + osId), "sk", s("EXECUCAO"), "status", s("AGENDADA"), "sagaId", s("saga-" + osId),
                "itens", itens, "agendadoEm", s("2026-11-02T14:00:00Z"));
    }

    private static String pagina(String pk, String sk) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((pk + "\n" + sk).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, AttributeValue> chave(String osId) {
        return Map.of("pk", s("OS#" + osId), "sk", s("EXECUCAO"));
    }

    @Test
    void ultimaPaginaNaoDevolveProximaPagina() {
        when(dynamo.scan(any(ScanRequest.class))).thenReturn(
                ScanResponse.builder().items(List.of(execucao("1"), execucao("2"))).build());

        PaginaExecucoes pagina = repository.listarExecucoes(5, null);

        assertThat(pagina.itens()).extracting("osId").containsExactly("1", "2");
        assertThat(pagina.proximaPagina()).isNull();
    }

    @Test
    void cortaNoLimiteEDevolveTokenDoUltimoItemDaPagina() {
        when(dynamo.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder()
                .items(List.of(execucao("1"), execucao("2"), execucao("3")))
                .lastEvaluatedKey(chave("3")).build());

        PaginaExecucoes pagina = repository.listarExecucoes(2, null);

        assertThat(pagina.itens()).extracting("osId").containsExactly("1", "2");
        assertThat(pagina.proximaPagina()).isEqualTo(pagina("OS#2", "EXECUCAO"));
    }

    @Test
    void itensExatosNoLimiteUsamAChaveDoScanComoToken() {
        when(dynamo.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder()
                .items(List.of(execucao("1"), execucao("2"))).lastEvaluatedKey(chave("2")).build());

        assertThat(repository.listarExecucoes(2, null).proximaPagina()).isEqualTo(pagina("OS#2", "EXECUCAO"));
    }

    @Test
    void continuaVarrendoAteCompletarOLimiteQuandoOFiltroDescartaItens() {
        when(dynamo.scan(any(ScanRequest.class))).thenReturn(
                ScanResponse.builder().items(List.of(execucao("1"))).lastEvaluatedKey(chave("x")).build(),
                ScanResponse.builder().items(List.of(execucao("2"), execucao("3"))).build());

        PaginaExecucoes pagina = repository.listarExecucoes(3, null);

        assertThat(pagina.itens()).hasSize(3);
        assertThat(pagina.proximaPagina()).isNull();
        verify(dynamo, times(2)).scan(any(ScanRequest.class));
    }

    @Test
    void retomaOScanAPartirDaPaginaRecebida() {
        when(dynamo.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(List.of(execucao("3"))).build());

        repository.listarExecucoes(2, pagina("OS#2", "EXECUCAO"));

        ArgumentCaptor<ScanRequest> captor = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamo).scan(captor.capture());
        assertThat(captor.getValue().exclusiveStartKey()).isEqualTo(chave("2"));
    }

    @Test
    void rejeitaPaginaInvalida() {
        assertThatThrownBy(() -> repository.listarExecucoes(2, "###"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("pagina inválida");
        assertThatThrownBy(() -> repository.listarExecucoes(2, pagina("OUTBOX#x", "RESULTADO")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("pagina inválida");
        assertThatThrownBy(() -> repository.listarExecucoes(2, Base64.getUrlEncoder().withoutPadding()
                .encodeToString("sem-quebra".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
