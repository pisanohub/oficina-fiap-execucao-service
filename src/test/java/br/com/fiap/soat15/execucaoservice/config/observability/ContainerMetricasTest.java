package br.com.fiap.soat15.execucaoservice.config.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerMetricasTest {
    @TempDir
    Path raiz;

    private SimpleMeterRegistry registrar() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ContainerMetricas(raiz).bindTo(registry);
        return registry;
    }

    private void escrever(String arquivo, String conteudo) throws IOException {
        Path caminho = raiz.resolve(arquivo);
        Files.createDirectories(caminho.getParent());
        Files.writeString(caminho, conteudo + "\n");
    }

    @Test
    void lePodComLimitesNoCgroupV2() throws IOException {
        escrever("memory.max", "536870912");
        escrever("memory.current", "104857600");
        escrever("cpu.max", "50000 100000");

        SimpleMeterRegistry registry = registrar();

        assertThat(registry.get("oficina.container.memoria.limite.bytes").gauge().value()).isEqualTo(536870912d);
        assertThat(registry.get("oficina.container.memoria.uso.bytes").gauge().value()).isEqualTo(104857600d);
        assertThat(registry.get("oficina.container.cpu.limite.nucleos").gauge().value()).isEqualTo(0.5);
    }

    @Test
    void usoDeMemoriaAcompanhaOArquivoACadaLeitura() throws IOException {
        escrever("memory.current", "100");
        SimpleMeterRegistry registry = registrar();
        assertThat(registry.get("oficina.container.memoria.uso.bytes").gauge().value()).isEqualTo(100d);
        escrever("memory.current", "250");
        assertThat(registry.get("oficina.container.memoria.uso.bytes").gauge().value()).isEqualTo(250d);
    }

    @Test
    void lePodComLimitesNoCgroupV1() throws IOException {
        escrever("memory/memory.limit_in_bytes", "268435456");
        escrever("memory/memory.usage_in_bytes", "1000");
        escrever("cpu/cpu.cfs_quota_us", "200000");
        escrever("cpu/cpu.cfs_period_us", "100000");

        SimpleMeterRegistry registry = registrar();

        assertThat(registry.get("oficina.container.memoria.limite.bytes").gauge().value()).isEqualTo(268435456d);
        assertThat(registry.get("oficina.container.memoria.uso.bytes").gauge().value()).isEqualTo(1000d);
        assertThat(registry.get("oficina.container.cpu.limite.nucleos").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void semLimiteNaoRegistraOsMedidoresDeLimite() throws IOException {
        escrever("memory.max", "max");
        escrever("cpu.max", "max 100000");

        SimpleMeterRegistry registry = registrar();

        assertThat(registry.find("oficina.container.memoria.limite.bytes").gauge()).isNull();
        assertThat(registry.find("oficina.container.cpu.limite.nucleos").gauge()).isNull();
    }

    @Test
    void foraDeUmContainerNaoRegistraNada() {
        assertThat(registrar().getMeters()).isEmpty();
    }

    @Test
    void interpretaValoresInvalidosOuIlimitadosComoAusentes() {
        assertThat(ContainerMetricas.interpretarBytes("9223372036854771712")).isEmpty();
        assertThat(ContainerMetricas.interpretarBytes("abc")).isEmpty();
        assertThat(ContainerMetricas.interpretarBytes("0")).isEmpty();
        assertThat(ContainerMetricas.interpretarCpuV1("-1", "100000")).isEmpty();
        assertThat(ContainerMetricas.interpretarCpuV2("100000")).isEmpty();
        assertThat(ContainerMetricas.interpretarCpuV2("x y")).isEmpty();
    }
}
