package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.DiagnosticoNaoEncontradoException;
import br.com.fiap.soat15.execucaoservice.application.VersaoDiagnosticoConflitanteException;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoDiagnosticoRepositoryTest {
    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final DynamoExecucaoRepository repository = new DynamoExecucaoRepository(dynamo, "execucao_service");
    private final Instant agora = Instant.parse("2026-11-02T14:00:00Z");

    private static AttributeValue s(String valor) { return AttributeValue.builder().s(valor).build(); }
    private static AttributeValue n(String valor) { return AttributeValue.builder().n(valor).build(); }

    private static Map<String, AttributeValue> diagnostico(String osId, String versao, String... itensId) {
        List<AttributeValue> itens = java.util.Arrays.stream(itensId).map(id -> AttributeValue.builder().m(Map.of(
                "tipo", s("PECA"), "itemId", s(id), "quantidade", n("2"))).build()).toList();
        return Map.of("pk", s("OS#" + osId), "sk", s("DIAGNOSTICO"), "status", s("EM_DIAGNOSTICO"),
                "versao", n(versao), "itens", AttributeValue.builder().l(itens).build(),
                "atualizadoEm", s("2026-11-02T13:00:00Z"));
    }

    private void bancoVazio() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
    }

    private void bancoCom(Map<String, AttributeValue> item) {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());
    }

    @Test
    void iniciaDiagnosticoNovoNaVersao1ComGravacaoCondicional() {
        bancoVazio();

        Diagnostico criado = repository.iniciarDiagnostico("123", agora);

        assertThat(criado.versao()).isEqualTo(1);
        assertThat(criado.itens()).isEmpty();
        assertThat(criado.status()).isEqualTo("EM_DIAGNOSTICO");
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());
        PutItemRequest request = captor.getValue();
        assertThat(request.item().get("pk").s()).isEqualTo("OS#123");
        assertThat(request.item().get("sk").s()).isEqualTo("DIAGNOSTICO");
        assertThat(request.item().get("versao").n()).isEqualTo("1");
        assertThat(request.conditionExpression()).isEqualTo("attribute_not_exists(pk)");
    }

    @Test
    void iniciarDiagnosticoExistenteDevolveOQueJaExisteSemGravar() {
        bancoCom(diagnostico("123", "3", "filtro"));

        Diagnostico existente = repository.iniciarDiagnostico("123", agora);

        assertThat(existente.versao()).isEqualTo(3);
        assertThat(existente.itens()).extracting(ItemSolicitado::itemId).containsExactly("filtro");
        verify(dynamo, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void corridaNaCriacaoDevolveODiagnosticoGravadoPeloOutro() {
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of()).build())
                .thenReturn(GetItemResponse.builder().item(diagnostico("123", "1")).build());
        when(dynamo.putItem(any(PutItemRequest.class))).thenThrow(ConditionalCheckFailedException.builder().build());

        assertThat(repository.iniciarDiagnostico("123", agora).versao()).isEqualTo(1);
    }

    @Test
    void corridaSemDiagnosticoVisivelPropagaAFalhaOriginal() {
        bancoVazio();
        when(dynamo.putItem(any(PutItemRequest.class))).thenThrow(ConditionalCheckFailedException.builder().build());

        assertThatThrownBy(() -> repository.iniciarDiagnostico("123", agora))
                .isInstanceOf(ConditionalCheckFailedException.class);
    }

    @Test
    void atualizaItensExigindoAVersaoEsperadaEIncrementandoAVersao() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder()
                .attributes(diagnostico("123", "2", "filtro", "pastilha")).build());

        Diagnostico atualizado = repository.atualizarItens("123", 1,
                List.of(new ItemSolicitado(TipoItem.PECA, "filtro", 2), new ItemSolicitado(TipoItem.PECA, "pastilha", 2)), agora);

        assertThat(atualizado.versao()).isEqualTo(2);
        assertThat(atualizado.itens()).hasSize(2);
        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(captor.capture());
        UpdateItemRequest request = captor.getValue();
        assertThat(request.key().get("pk").s()).isEqualTo("OS#123");
        assertThat(request.conditionExpression()).isEqualTo("#versao = :esperada AND #status = :diagnostico");
        assertThat(request.expressionAttributeValues().get(":esperada").n()).isEqualTo("1");
        assertThat(request.expressionAttributeValues().get(":nova").n()).isEqualTo("2");
        assertThat(request.expressionAttributeValues().get(":itens").l()).hasSize(2);
    }

    @Test
    void versaoDesatualizadaGeraConflito() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(ConditionalCheckFailedException.builder().build());
        bancoCom(diagnostico("123", "5"));

        assertThatThrownBy(() -> repository.atualizarItens("123", 1, List.of(new ItemSolicitado(TipoItem.PECA, "filtro", 1)), agora))
                .isInstanceOf(VersaoDiagnosticoConflitanteException.class);
    }

    @Test
    void diagnosticoInexistenteGeraNaoEncontrado() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(ConditionalCheckFailedException.builder().build());
        bancoVazio();

        assertThatThrownBy(() -> repository.atualizarItens("123", 1, List.of(new ItemSolicitado(TipoItem.PECA, "filtro", 1)), agora))
                .isInstanceOf(DiagnosticoNaoEncontradoException.class);
    }

    @Test
    void buscaDiagnosticoMapeandoOsItens() {
        bancoCom(diagnostico("123", "4", "filtro", "pastilha"));

        Optional<Diagnostico> encontrado = repository.buscarDiagnostico("123");

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().osId()).isEqualTo("123");
        assertThat(encontrado.get().versao()).isEqualTo(4);
        assertThat(encontrado.get().itens()).extracting(ItemSolicitado::itemId).containsExactly("filtro", "pastilha");
        assertThat(encontrado.get().atualizadoEm()).isEqualTo(Instant.parse("2026-11-02T13:00:00Z"));
    }

    @Test
    void buscaDeDiagnosticoInexistenteDevolveVazio() {
        bancoVazio();
        assertThat(repository.buscarDiagnostico("999")).isEmpty();
    }
}
