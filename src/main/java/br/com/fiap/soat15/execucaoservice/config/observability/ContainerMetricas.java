package br.com.fiap.soat15.execucaoservice.config.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

@Component
public class ContainerMetricas implements MeterBinder {
    private static final long SEM_LIMITE = 1L << 60;
    private final Path raiz;

    @Autowired
    public ContainerMetricas() {
        this(Path.of("/sys/fs/cgroup"));
    }

    ContainerMetricas(Path raiz) {
        this.raiz = raiz;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        OptionalLong limiteMemoria = lerBytes("memory.max", "memory/memory.limit_in_bytes");
        limiteMemoria.ifPresent(limite -> Gauge.builder("oficina.container.memoria.limite.bytes", () -> limite)
                .description("Limite de memória do container").baseUnit("bytes").register(registry));

        if (existe("memory.current", "memory/memory.usage_in_bytes")) {
            Gauge.builder("oficina.container.memoria.uso.bytes",
                            () -> lerBytes("memory.current", "memory/memory.usage_in_bytes").orElse(0))
                    .description("Memória em uso pelo container").baseUnit("bytes").register(registry);
        }

        limiteCpu().ifPresent(limite -> Gauge.builder("oficina.container.cpu.limite.nucleos", () -> limite)
                .description("Limite de CPU do container, em núcleos").register(registry));
    }

    private OptionalDouble limiteCpu() {
        Optional<String> v2 = ler("cpu.max");
        if (v2.isPresent()) {
            return interpretarCpuV2(v2.get());
        }
        Optional<String> quota = ler("cpu/cpu.cfs_quota_us");
        Optional<String> periodo = ler("cpu/cpu.cfs_period_us");
        return quota.isPresent() && periodo.isPresent()
                ? interpretarCpuV1(quota.get(), periodo.get()) : OptionalDouble.empty();
    }

    private OptionalLong lerBytes(String v2, String v1) {
        return ler(v2).or(() -> ler(v1)).map(ContainerMetricas::interpretarBytes).orElse(OptionalLong.empty());
    }

    private boolean existe(String v2, String v1) {
        return Files.isReadable(raiz.resolve(v2)) || Files.isReadable(raiz.resolve(v1));
    }

    private Optional<String> ler(String arquivo) {
        try {
            Path caminho = raiz.resolve(arquivo);
            return Files.isReadable(caminho) ? Optional.of(Files.readString(caminho).trim()) : Optional.empty();
        } catch (IOException | RuntimeException erro) {
            return Optional.empty();
        }
    }

    static OptionalLong interpretarBytes(String conteudo) {
        try {
            long valor = Long.parseLong(conteudo.trim());
            return valor <= 0 || valor >= SEM_LIMITE ? OptionalLong.empty() : OptionalLong.of(valor);
        } catch (NumberFormatException invalido) {
            return OptionalLong.empty();
        }
    }

    static OptionalDouble interpretarCpuV2(String conteudo) {
        String[] partes = conteudo.trim().split("\\s+");
        if (partes.length != 2 || "max".equals(partes[0])) {
            return OptionalDouble.empty();
        }
        return interpretarCpuV1(partes[0], partes[1]);
    }

    static OptionalDouble interpretarCpuV1(String quota, String periodo) {
        try {
            long q = Long.parseLong(quota.trim());
            long p = Long.parseLong(periodo.trim());
            return q <= 0 || p <= 0 ? OptionalDouble.empty() : OptionalDouble.of((double) q / p);
        } catch (NumberFormatException invalido) {
            return OptionalDouble.empty();
        }
    }
}
