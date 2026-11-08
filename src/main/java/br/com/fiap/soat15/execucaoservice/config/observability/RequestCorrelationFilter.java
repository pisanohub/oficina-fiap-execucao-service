package br.com.fiap.soat15.execucaoservice.config.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern ROTA_COM_OS = Pattern.compile("^/api/v1/(?:execucoes|diagnosticos)/([^/]+)");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (!requestIdValido(requestId)) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(CamposCorrelacao.REQUEST_ID, requestId);
        Matcher rota = ROTA_COM_OS.matcher(request.getRequestURI());
        if (rota.find()) {
            MDC.put(CamposCorrelacao.OS_ID, rota.group(1));
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long inicio = System.nanoTime();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duracaoMs = (System.nanoTime() - inicio) / 1_000_000;
            log.info(
                    "Requisição HTTP concluída method={} path={} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), duracaoMs
            );
            MDC.remove(CamposCorrelacao.REQUEST_ID);
            MDC.remove(CamposCorrelacao.OS_ID);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    private boolean requestIdValido(String requestId) {
        return requestId != null
                && !requestId.isBlank()
                && requestId.length() <= 100
                && requestId.matches("[A-Za-z0-9._-]+");
    }
}
