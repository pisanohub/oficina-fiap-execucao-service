package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import java.util.Arrays;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import br.com.fiap.soat15.execucaoservice.application.ExecucaoJaAgendadaException;
import br.com.fiap.soat15.execucaoservice.application.PecaIndisponivelException;
import br.com.fiap.soat15.execucaoservice.contract.AgendarExecucaoCommand;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoExecucaoRepositoryTest {
    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final DynamoExecucaoRepository repository = new DynamoExecucaoRepository(dynamo, "execucao_service");
    private final Instant agora = Instant.parse("2026-11-02T14:00:00Z");

    @BeforeEach
    void preparar() {
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of()).build());
    }

    @Test
    void reservaSomentePecasEGravaExecucaoOperacaoEOutboxNaMesmaTransacao() {
        AgendarExecucaoCommand comando = comando(List.of(
                new ItemSolicitado(TipoItem.SERVICO, "troca-oleo", 1),
                new ItemSolicitado(TipoItem.PECA, "filtro", 2)));

        var agendamento = repository.agendarExecucao(comando, agora);

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(captor.capture());
        var escritas = captor.getValue().transactItems();
        assertThat(escritas).hasSize(4);
        assertThat(escritas.get(0).update().key().get("pk").s()).isEqualTo("PECA#filtro");
        assertThat(escritas.get(0).update().conditionExpression())
                .contains("quantidadeEstoque >= :quantidade");
        assertThat(escritas.get(1).put().item().get("pk").s()).isEqualTo("OS#123");
        assertThat(escritas.get(1).put().item().get("sk").s()).isEqualTo("EXECUCAO");
        assertThat(escritas.get(1).put().item().get("status").s()).isEqualTo("AGENDADA");
        assertThat(escritas.get(2).put().item().get("pk").s()).isEqualTo("OPERACAO#op-123");
        assertThat(escritas.get(2).put().item().get("itens").l()).hasSize(2);
        assertThat(escritas.get(3).put().item().get("pk").s()).isEqualTo("OUTBOX#op-123");
        assertThat(escritas.get(3).put().item().get("published").bool()).isFalse();
        assertThat(agendamento.osId()).isEqualTo("123");
    }

    @Test
    void iniciarGravaEstadoEEventoNaMesmaTransacao() {
        repository.agendarExecucao(comando(List.of(new ItemSolicitado(TipoItem.PECA, "filtro", 1))), agora);
        var captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(captor.capture());
        var execucao = captor.getValue().transactItems().get(1).put().item();
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(execucao).build());
        var iniciada = repository.iniciarExecucao("123", agora.plusSeconds(60));
        assertThat(iniciada.status()).isEqualTo("EM_EXECUCAO");
        assertThat(iniciada.iniciadaEm()).isEqualTo(agora.plusSeconds(60));
        verify(dynamo, times(2)).transactWriteItems(captor.capture());
        var transacao = captor.getAllValues().get(captor.getAllValues().size() - 1).transactItems();
        assertThat(transacao.get(0).update().conditionExpression()).contains(":agendada");
        assertThat(transacao.get(1).put().item().get("type").s()).isEqualTo("ExecucaoIniciada");
    }

    @Test
    void repeticaoDaMesmaOperacaoNaoReservaDeNovoERejeitaItensDiferentes() {
        AgendarExecucaoCommand original = comando(List.of(new ItemSolicitado(TipoItem.PECA, "filtro", 1)));
        repository.agendarExecucao(original, agora);
        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(captor.capture());
        Map<String, AttributeValue> operacaoGravada = captor.getValue().transactItems().get(2).put().item();
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(operacaoGravada).build());

        assertThat(repository.agendarExecucao(original, agora.plusSeconds(60)).agendadoEm()).isEqualTo(agora);
        assertThatThrownBy(() -> repository.agendarExecucao(comando(List.of(
                new ItemSolicitado(TipoItem.PECA, "filtro", 2))), agora.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("operationId reutilizado");
        verify(dynamo, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void estoqueInsuficienteGeraFalhaDeNegocioIdentificavel() {
        when(dynamo.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(cancelada("ConditionalCheckFailed", "None", "None", "None"))
                .thenReturn(TransactWriteItemsResponse.builder().build());
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build(), GetItemResponse.builder().build(),
                        GetItemResponse.builder().item(Map.of("ativo", AttributeValue.builder().bool(true).build(),
                                "quantidadeEstoque", AttributeValue.builder().n("1").build())).build());

        assertThatThrownBy(() -> repository.agendarExecucao(comando(List.of(
                new ItemSolicitado(TipoItem.PECA, "filtro", 2))), agora))
                .isInstanceOf(PecaIndisponivelException.class).hasMessageContaining("filtro");
        var captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo, times(2)).transactWriteItems(captor.capture());
        var rejeicao = captor.getAllValues().get(1).transactItems();
        assertThat(rejeicao.get(1).put().item().get("type").s()).isEqualTo("ExecucaoRejeitada");
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(rejeicao.get(0).put().item()).build());
        assertThatThrownBy(() -> repository.agendarExecucao(comando(List.of(
                new ItemSolicitado(TipoItem.PECA, "filtro", 2))), agora.plusSeconds(1)))
                .isInstanceOf(PecaIndisponivelException.class);
        verify(dynamo, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void outraOperacaoParaMesmaOsNaoCriaSegundaExecucao() {
        when(dynamo.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(cancelada("ConditionalCheckFailed", "None", "None"));

        assertThatThrownBy(() -> repository.agendarExecucao(comando(List.of(
                new ItemSolicitado(TipoItem.SERVICO, "troca-oleo", 1))), agora))
                .isInstanceOf(ExecucaoJaAgendadaException.class).hasMessageContaining("OS 123");
    }

    private TransactionCanceledException cancelada(String... codigos) {
        return TransactionCanceledException.builder().cancellationReasons(
                Arrays.stream(codigos).map(codigo -> CancellationReason.builder().code(codigo).build())
                        .toList()).build();
    }

    private AgendarExecucaoCommand comando(List<ItemSolicitado> itens) {
        return new AgendarExecucaoCommand(1, "02539ee2-c754-4bb7-b178-4b328de9dbd5", "op-123",
                "AgendarExecucao", "saga-123", "123", "pagamento-123", agora.toString(),
                new AgendarExecucaoCommand.Payload("orc-123", 2, "pag-123", itens));
    }
}
