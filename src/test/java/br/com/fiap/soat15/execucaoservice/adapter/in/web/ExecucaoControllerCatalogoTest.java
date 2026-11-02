package br.com.fiap.soat15.execucaoservice.adapter.in.web;

import br.com.fiap.soat15.execucaoservice.application.ExecucaoService;
import br.com.fiap.soat15.execucaoservice.application.ItemCatalogoNaoEncontradoException;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecucaoControllerCatalogoTest {
    private final ExecucaoService service = mock(ExecucaoService.class);
    private MockMvc mvc;
    private final ItemCatalogo filtro = new ItemCatalogo(TipoItem.PECA, "filtro", "Filtro",
            new BigDecimal("25.00"), true, 10);

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(new ExecucaoController(service))
                .setControllerAdvice(new ExecucaoExceptionHandler()).build();
    }

    @Test
    void listaCatalogoComFiltros() throws Exception {
        when(service.listarCatalogo(TipoItem.PECA, true)).thenReturn(List.of(filtro));
        mvc.perform(get("/api/v1/catalogo?tipo=PECA&ativo=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemId").value("filtro"))
                .andExpect(jsonPath("$[0].quantidadeEstoque").value(10));
    }

    @Test
    void tipoDesconhecidoRetornaBadRequest() throws Exception {
        mvc.perform(get("/api/v1/catalogo?tipo=XYZ")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/catalogo/XYZ/filtro")).andExpect(status().isBadRequest());
    }

    @Test
    void consultaItemExistenteEInexistente() throws Exception {
        when(service.consultarItemCatalogo(TipoItem.PECA, "filtro")).thenReturn(filtro);
        when(service.consultarItemCatalogo(TipoItem.PECA, "nada"))
                .thenThrow(new ItemCatalogoNaoEncontradoException(TipoItem.PECA, "nada"));
        mvc.perform(get("/api/v1/catalogo/PECA/filtro")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Filtro"));
        mvc.perform(get("/api/v1/catalogo/PECA/nada")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Item de catálogo não encontrado: PECA/nada"));
    }

    @Test
    void atualizaItemPeloCorpoDaRequisicao() throws Exception {
        when(service.atualizarItemCatalogo(eq(TipoItem.PECA), eq("filtro"), eq("Filtro novo"),
                eq(new BigDecimal("30.50")), eq(false))).thenReturn(filtro);
        mvc.perform(put("/api/v1/catalogo/PECA/filtro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Filtro novo\",\"precoUnitario\":30.50,\"ativo\":false}"))
                .andExpect(status().isOk());
        verify(service).atualizarItemCatalogo(TipoItem.PECA, "filtro", "Filtro novo", new BigDecimal("30.50"), false);
    }

    @Test
    void dadosInvalidosNaAtualizacaoRetornamBadRequest() throws Exception {
        when(service.atualizarItemCatalogo(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("ativo é obrigatório"));
        mvc.perform(put("/api/v1/catalogo/PECA/filtro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Filtro\",\"precoUnitario\":1.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("ativo é obrigatório"));
    }

    @Test
    void reposicaoEAjusteDeEstoque() throws Exception {
        when(service.reporEstoque(TipoItem.PECA, "filtro", 5)).thenReturn(filtro);
        when(service.ajustarEstoque(TipoItem.PECA, "filtro", 0)).thenReturn(filtro);
        mvc.perform(post("/api/v1/catalogo/PECA/filtro/estoque/reposicao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantidade\":5}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/catalogo/PECA/filtro/estoque").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantidadeEstoque\":0}")).andExpect(status().isOk());
        verify(service).reporEstoque(TipoItem.PECA, "filtro", 5);
        verify(service).ajustarEstoque(TipoItem.PECA, "filtro", 0);
    }

    @Test
    void estoqueDePecaInexistenteRetornaNotFound() throws Exception {
        when(service.reporEstoque(TipoItem.PECA, "nada", 1))
                .thenThrow(new ItemCatalogoNaoEncontradoException(TipoItem.PECA, "nada"));
        mvc.perform(post("/api/v1/catalogo/PECA/nada/estoque/reposicao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantidade\":1}")).andExpect(status().isNotFound());
    }
}
