package br.com.fiap.soat15.execucaoservice.adapter.in.web;

import br.com.fiap.soat15.execucaoservice.application.ExecucaoService;
import br.com.fiap.soat15.execucaoservice.domain.model.ExecucaoTrabalho;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.PaginaExecucoes;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecucaoControllerPaginacaoTest {
    private final ExecucaoService service = mock(ExecucaoService.class);
    private MockMvc mvc;
    private final ExecucaoTrabalho execucao = new ExecucaoTrabalho("1", "AGENDADA", "saga-1",
            List.of(new ItemSolicitado(TipoItem.PECA, "filtro", 1)), Instant.parse("2026-11-02T14:00:00Z"), null, null);

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(new ExecucaoController(service))
                .setControllerAdvice(new ExecucaoExceptionHandler()).build();
    }

    @Test
    void semLimiteDevolveTodasSemCabecalhoDeProximaPagina() throws Exception {
        when(service.listarExecucoes()).thenReturn(List.of(execucao));
        mvc.perform(get("/api/v1/execucoes")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].osId").value("1"))
                .andExpect(header().doesNotExist("X-Proxima-Pagina"));
    }

    @Test
    void comLimiteDevolveListaEInformaProximaPaginaNoCabecalho() throws Exception {
        when(service.listarExecucoes(1, null)).thenReturn(new PaginaExecucoes(List.of(execucao), "abc"));
        mvc.perform(get("/api/v1/execucoes?limite=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].osId").value("1"))
                .andExpect(header().string("X-Proxima-Pagina", "abc"));
    }

    @Test
    void ultimaPaginaNaoTemCabecalhoEAceitaATokenAnterior() throws Exception {
        when(service.listarExecucoes(1, "abc")).thenReturn(new PaginaExecucoes(List.of(execucao), null));
        mvc.perform(get("/api/v1/execucoes?limite=1&pagina=abc")).andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Proxima-Pagina"));
        verify(service).listarExecucoes(1, "abc");
    }

    @Test
    void paginaSemLimiteERejeitada() throws Exception {
        mvc.perform(get("/api/v1/execucoes?pagina=abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("pagina exige limite"));
        verifyNoMoreInteractions(service);
    }

    @Test
    void limiteInvalidoRetornaBadRequest() throws Exception {
        when(service.listarExecucoes(0, null)).thenThrow(new IllegalArgumentException("limite deve estar entre 1 e 100"));
        mvc.perform(get("/api/v1/execucoes?limite=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/execucoes?limite=abc")).andExpect(status().isBadRequest());
    }
}
