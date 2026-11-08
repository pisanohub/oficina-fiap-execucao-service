package br.com.fiap.soat15.execucaoservice.config.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    private Map<String, String> executar(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        Map<String, String> mdc = new HashMap<>();
        filter.doFilter(request, response, (req, res) -> {
            mdc.put(CamposCorrelacao.REQUEST_ID, MDC.get(CamposCorrelacao.REQUEST_ID));
            mdc.put(CamposCorrelacao.OS_ID, MDC.get(CamposCorrelacao.OS_ID));
        });
        return mdc;
    }

    @Test
    void deveGerarRequestIdQuandoHeaderNaoFoiInformado() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, String> mdc = executar(new MockHttpServletRequest("POST", "/api/v1/execucoes/123/inicio"), response);

        assertThat(mdc.get(CamposCorrelacao.REQUEST_ID)).isNotBlank().isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(mdc.get(CamposCorrelacao.OS_ID)).isEqualTo("123");
        assertThat(MDC.get(CamposCorrelacao.REQUEST_ID)).isNull();
        assertThat(MDC.get(CamposCorrelacao.OS_ID)).isNull();
    }

    @Test
    void devePropagarRequestIdRecebido() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/diagnosticos/456");
        request.addHeader("X-Request-Id", "teste-correlacao-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, String> mdc = executar(request, response);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("teste-correlacao-123");
        assertThat(mdc.get(CamposCorrelacao.OS_ID)).isEqualTo("456");
    }

    @Test
    void deveTrocarRequestIdInvalidoESemOsNaRotaNaoPoeOsId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/catalogo");
        request.addHeader("X-Request-Id", "inválido com espaço");

        Map<String, String> mdc = executar(request, new MockHttpServletResponse());

        assertThat(mdc.get(CamposCorrelacao.REQUEST_ID)).isNotEqualTo("inválido com espaço");
        assertThat(mdc.get(CamposCorrelacao.OS_ID)).isNull();
    }

    @Test
    void deveIgnorarRotasForaDaApi() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        executar(new MockHttpServletRequest("GET", "/actuator/health"), response);

        assertThat(response.getHeader("X-Request-Id")).isNull();
    }
}
