package br.com.fiap.soat15.execucaoservice.config.observability;

import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RastreamentoDeTeste {
    public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    public static final String SPAN_PAI = "00f067aa0ba902b7";
    public static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_PAI + "-01";

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();
    private final ContextoRastreamento contexto;
    private final ObservationRegistry registry = ObservationRegistry.create();

    public RastreamentoDeTeste() {
        var otel = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(new Coletor())).build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        var tracerOtel = otel.getTracer("testes");
        var tracer = new OtelTracer(tracerOtel, new OtelCurrentTraceContext(), evento -> { });
        var propagator = new OtelPropagator(otel.getPropagators(), tracerOtel);
        contexto = new ContextoRastreamento(tracer, propagator);
        registry.observationConfig().observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));
    }

    public ContextoRastreamento contexto() {
        return contexto;
    }

    public ObservacaoMensagens observacao() {
        return new ObservacaoMensagens(registry, contexto);
    }

    public SpanData unicoSpan() {
        if (spans.size() != 1) {
            throw new AssertionError("esperado um span, encontrados " + spans.size());
        }
        return spans.get(0);
    }

    private final class Coletor implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> lote) {
            spans.addAll(lote);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
