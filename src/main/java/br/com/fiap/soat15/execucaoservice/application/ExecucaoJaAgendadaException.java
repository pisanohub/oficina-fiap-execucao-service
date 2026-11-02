package br.com.fiap.soat15.execucaoservice.application;

public class ExecucaoJaAgendadaException extends RuntimeException {
    public ExecucaoJaAgendadaException(String osId, Throwable causa) {
        super("Já existe uma execução agendada para a OS " + osId, causa);
    }
}
