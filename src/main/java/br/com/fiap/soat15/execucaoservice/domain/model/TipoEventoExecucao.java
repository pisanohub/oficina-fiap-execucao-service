package br.com.fiap.soat15.execucaoservice.domain.model;

public enum TipoEventoExecucao {
    AGENDADA("ExecucaoAgendada"),
    REJEITADA("ExecucaoRejeitada"),
    INICIADA("ExecucaoIniciada"),
    CONCLUIDA("ExecucaoConcluida"),
    CANCELADA("ExecucaoCancelada"),
    CANCELAMENTO_RECUSADO("CancelamentoExecucaoRecusado");

    private final String codigo;

    TipoEventoExecucao(String codigo) {
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }

}
