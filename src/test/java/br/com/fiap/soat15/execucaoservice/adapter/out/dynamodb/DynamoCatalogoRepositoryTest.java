package br.com.fiap.soat15.execucaoservice.adapter.out.dynamodb;

import br.com.fiap.soat15.execucaoservice.application.ItemCatalogoNaoEncontradoException;
import br.com.fiap.soat15.execucaoservice.domain.model.AtualizacaoItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoCatalogoRepositoryTest {
    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final DynamoExecucaoRepository repository = new DynamoExecucaoRepository(dynamo, "execucao_service");

    private static AttributeValue s(String valor) { return AttributeValue.builder().s(valor).build(); }
    private static AttributeValue n(String valor) { return AttributeValue.builder().n(valor).build(); }
    private static AttributeValue b(boolean valor) { return AttributeValue.builder().bool(valor).build(); }

    private static Map<String, AttributeValue> peca(String id, String nome, String preco, boolean ativo, String estoque) {
        return Map.of("pk", s("PECA#" + id), "sk", s("CADASTRO"), "tipo", s("PECA"), "itemId", s(id),
                "nome", s(nome), "precoUnitario", n(preco), "ativo", b(ativo), "quantidadeEstoque", n(estoque));
    }

    private static Map<String, AttributeValue> servico(String id, String nome, String preco) {
        return Map.of("pk", s("SERVICO#" + id), "sk", s("CADASTRO"), "tipo", s("SERVICO"), "itemId", s(id),
                "nome", s(nome), "precoUnitario", n(preco), "ativo", b(true));
    }

    @Test
    void listaTodasAsPaginasOrdenadasPorTipoEId() {
        Map<String, AttributeValue> cursor = Map.of("pk", s("PECA#b"), "sk", s("CADASTRO"));
        when(dynamo.scan(any(ScanRequest.class))).thenReturn(
                ScanResponse.builder().items(List.of(peca("b", "B", "2.00", true, "4"), servico("troca", "Troca", "90.00")))
                        .lastEvaluatedKey(cursor).build(),
                ScanResponse.builder().items(List.of(peca("a", "A", "1.50", false, "0"))).build());

        List<ItemCatalogo> itens = repository.listarItensCatalogo(null, null);

        assertThat(itens).extracting(ItemCatalogo::itemId).containsExactly("a", "b", "troca");
        assertThat(itens.get(0).quantidadeEstoque()).isZero();
        assertThat(itens.get(2).quantidadeEstoque()).isNull();
        ArgumentCaptor<ScanRequest> capturado = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamo, times(2)).scan(capturado.capture());
        assertThat(capturado.getAllValues().get(0).filterExpression()).isEqualTo("sk = :sk");
        assertThat(capturado.getAllValues().get(1).exclusiveStartKey()).isEqualTo(cursor);
    }

    @Test
    void aplicaFiltrosDeTipoEAtivoNaVarredura() {
        when(dynamo.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(List.of()).build());

        repository.listarItensCatalogo(TipoItem.PECA, true);

        ArgumentCaptor<ScanRequest> capturado = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamo).scan(capturado.capture());
        ScanRequest request = capturado.getValue();
        assertThat(request.filterExpression()).isEqualTo("sk = :sk AND begins_with(pk, :prefixo) AND ativo = :ativo");
        assertThat(request.expressionAttributeValues().get(":prefixo").s()).isEqualTo("PECA#");
        assertThat(request.expressionAttributeValues().get(":ativo").bool()).isTrue();
    }

    @Test
    void atualizaApenasNomePrecoEAtivoDeFormaCondicional() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder()
                .attributes(peca("filtro", "Filtro novo", "30.50", false, "3")).build());

        ItemCatalogo atualizado = repository.atualizarItemCatalogo(TipoItem.PECA, "filtro",
                new AtualizacaoItemCatalogo("Filtro novo", new BigDecimal("30.50"), false));

        assertThat(atualizado.nome()).isEqualTo("Filtro novo");
        assertThat(atualizado.quantidadeEstoque()).isEqualTo(3);
        ArgumentCaptor<UpdateItemRequest> capturado = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(capturado.capture());
        UpdateItemRequest request = capturado.getValue();
        assertThat(request.key().get("pk").s()).isEqualTo("PECA#filtro");
        assertThat(request.key().get("sk").s()).isEqualTo("CADASTRO");
        assertThat(request.updateExpression()).doesNotContain("quantidadeEstoque");
        assertThat(request.conditionExpression()).isEqualTo("attribute_exists(pk)");
    }

    @Test
    void atualizacaoDeItemInexistenteFalhaComoNaoEncontrado() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(ConditionalCheckFailedException.builder().build());
        assertThatThrownBy(() -> repository.atualizarItemCatalogo(TipoItem.SERVICO, "x",
                new AtualizacaoItemCatalogo("X", new BigDecimal("1.00"), true)))
                .isInstanceOf(ItemCatalogoNaoEncontradoException.class);
    }

    @Test
    void reposicaoSomaNoBancoSemLerAntes() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder()
                .attributes(peca("filtro", "Filtro", "25.00", true, "10")).build());

        assertThat(repository.reporEstoque("filtro", 7).quantidadeEstoque()).isEqualTo(10);

        ArgumentCaptor<UpdateItemRequest> capturado = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(capturado.capture());
        UpdateItemRequest request = capturado.getValue();
        assertThat(request.key().get("pk").s()).isEqualTo("PECA#filtro");
        assertThat(request.updateExpression()).isEqualTo("SET quantidadeEstoque = quantidadeEstoque + :quantidade");
        assertThat(request.expressionAttributeValues().get(":quantidade").n()).isEqualTo("7");
        assertThat(request.conditionExpression()).contains("attribute_exists(quantidadeEstoque)");
    }

    @Test
    void ajusteDefineOEstoqueAbsoluto() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder()
                .attributes(peca("filtro", "Filtro", "25.00", true, "0")).build());

        assertThat(repository.ajustarEstoque("filtro", 0).quantidadeEstoque()).isZero();

        ArgumentCaptor<UpdateItemRequest> capturado = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(capturado.capture());
        assertThat(capturado.getValue().updateExpression()).isEqualTo("SET quantidadeEstoque = :quantidade");
        assertThat(capturado.getValue().expressionAttributeValues().get(":quantidade").n()).isEqualTo("0");
    }

    @Test
    void estoqueDePecaInexistenteFalhaComoNaoEncontrado() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(ConditionalCheckFailedException.builder().build());
        assertThatThrownBy(() -> repository.reporEstoque("nada", 1)).isInstanceOf(ItemCatalogoNaoEncontradoException.class);
        assertThatThrownBy(() -> repository.ajustarEstoque("nada", 1)).isInstanceOf(ItemCatalogoNaoEncontradoException.class);
    }
}
