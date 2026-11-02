package br.com.fiap.soat15.execucaoservice.application;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository {
    List<OutboxEvent> pendentes(int limite);
    boolean reservar(OutboxEvent evento, String dono, Instant agora, Instant ate);
    void marcarPublicado(OutboxEvent evento, String dono, Instant agora);
}
