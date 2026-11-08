package br.com.fiap.soat15.execucaoservice.config.observability;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ContextoRastreamento {
    private static final String TRACEPARENT = "traceparent";
    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    public ContextoRastreamento(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this(tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    public ContextoRastreamento(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public static ContextoRastreamento desligado() {
        return new ContextoRastreamento(Tracer.NOOP, Propagator.NOOP);
    }

    public Map<String, String> capturar() {
        TraceContext atual = tracer.currentTraceContext().context();
        if (atual == null || atual.traceId().isBlank()) {
            return Map.of();
        }
        Map<String, String> contexto = new HashMap<>();
        propagator.inject(atual, contexto, Map::put);
        return contexto;
    }

    public CurrentTraceContext.Scope restaurar(Map<String, String> contexto) {
        String[] partes = contexto.getOrDefault(TRACEPARENT, "").split("-");
        if (partes.length != 4 || partes[1].length() != 32 || partes[2].length() != 16) {
            return () -> { };
        }
        TraceContext restaurado = tracer.traceContextBuilder()
                .traceId(partes[1])
                .spanId(partes[2])
                .sampled((Integer.parseInt(partes[3], 16) & 1) == 1)
                .build();
        return tracer.currentTraceContext().newScope(restaurado);
    }
}
