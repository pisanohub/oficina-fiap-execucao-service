package br.com.fiap.soat15.execucaoservice.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ExecucaoMetricas {
    private final MeterRegistry registry;
    private final AtomicLong atrasoOutboxSegundos = new AtomicLong();

    public ExecucaoMetricas(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("oficina.execucao.outbox.atraso", atrasoOutboxSegundos, AtomicLong::get)
                .description("Idade do evento pendente mais antigo da outbox")
                .baseUnit("seconds").register(registry);
    }

    public void agendamentoAceito(int pecasReservadas) {
        registry.counter("oficina.execucao.agendamentos", "resultado", "agendada").increment();
        if (pecasReservadas > 0) {
            registry.counter("oficina.execucao.pecas.reservadas").increment(pecasReservadas);
        }
    }

    public void agendamentoRejeitado() {
        registry.counter("oficina.execucao.agendamentos", "resultado", "rejeitada").increment();
    }

    public void esperaNaFila(Duration espera) {
        Timer.builder("oficina.execucao.fila.espera")
                .description("Tempo entre o agendamento e o início da execução")
                .publishPercentileHistogram().register(registry).record(espera);
    }

    public void execucaoConcluida(Duration duracao) {
        Timer.builder("oficina.execucao.duracao")
                .description("Tempo entre o início e a conclusão da execução")
                .publishPercentileHistogram().register(registry).record(duracao);
    }

    public void estoqueReposto(int quantidade) {
        registry.counter("oficina.execucao.estoque.reposto").increment(quantidade);
    }

    public void estoqueAjustado() {
        registry.counter("oficina.execucao.estoque.ajustes").increment();
    }

    public void eventoPublicado(String tipo) {
        registry.counter("oficina.execucao.eventos.publicados", "tipo", tipo).increment();
    }

    public void falhaPublicacao() {
        registry.counter("oficina.execucao.eventos.falhas").increment();
    }

    public void atrasoDaOutbox(Duration atraso) {
        atrasoOutboxSegundos.set(Math.max(0, atraso.toSeconds()));
    }

    public void comandoProcessado(String tipo, Duration duracao) {
        registry.counter("oficina.execucao.comandos.processados", "tipo", tipo).increment();
        Timer.builder("oficina.execucao.comandos.duracao").tag("tipo", tipo)
                .description("Tempo de processamento de um comando recebido do SQS")
                .publishPercentileHistogram().register(registry).record(duracao);
    }

    public void comandoFalhou(String tipo, boolean permanente) {
        registry.counter("oficina.execucao.comandos.falhas", "tipo", tipo,
                "natureza", permanente ? "permanente" : "transitoria").increment();
    }
}
