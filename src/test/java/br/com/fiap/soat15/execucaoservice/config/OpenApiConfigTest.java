package br.com.fiap.soat15.execucaoservice.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class OpenApiConfigTest {
    private static final String LISTAR_EXECUCOES = "$.paths['/api/v1/execucoes'].get";
    private static final String INICIAR_EXECUCAO = "$.paths['/api/v1/execucoes/{osId}/inicio'].post";

    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void documentaTituloETagsDaApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Execução Service API"))
                .andExpect(jsonPath("$.info.version").value("v1"))
                .andExpect(jsonPath("$.tags[*].name")
                        .value(containsInAnyOrder("Catálogo", "Estoque", "Diagnóstico", "Cotação", "Execuções")))
                .andExpect(jsonPath("$.paths['/api/v1/cotacoes'].post.tags").value(hasItem("Cotação")))
                .andExpect(jsonPath("$.paths['/api/v1/catalogo/{tipo}/{itemId}/estoque'].put.tags").value(hasItem("Estoque")));
    }

    @Test
    void documentaCodigosDeRespostaECabecalhos() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(INICIAR_EXECUCAO + ".summary").value("Iniciar a execução"))
                .andExpect(jsonPath(INICIAR_EXECUCAO + ".responses").value(hasKey("409")))
                .andExpect(jsonPath(INICIAR_EXECUCAO + ".responses").value(not(hasKey("400"))))
                .andExpect(jsonPath(INICIAR_EXECUCAO + ".responses['404'].content['application/problem+json'].schema['$ref']")
                        .value("#/components/schemas/Problema"))
                .andExpect(jsonPath(INICIAR_EXECUCAO + ".parameters[?(@.name == 'osId')].description")
                        .value(hasItem("Identificador da ordem de serviço (OS).")))
                .andExpect(jsonPath(INICIAR_EXECUCAO + ".parameters[?(@.name == 'X-Request-Id')].in").value(hasItem("header")))
                .andExpect(jsonPath(LISTAR_EXECUCOES + ".responses['200'].headers").value(hasKey("X-Proxima-Pagina")))
                .andExpect(jsonPath(LISTAR_EXECUCOES + ".responses['200'].headers").value(hasKey("X-Request-Id")));
    }

    @Test
    void documentaExemplosDosModelos() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ItemCatalogo.properties.itemId.example").value("filtro-oleo"))
                .andExpect(jsonPath("$.components.schemas.ExecucaoTrabalho.properties.status.enum")
                        .value(containsInAnyOrder("AGENDADA", "EM_EXECUCAO", "CONCLUIDA", "CANCELADA")));
    }
}
