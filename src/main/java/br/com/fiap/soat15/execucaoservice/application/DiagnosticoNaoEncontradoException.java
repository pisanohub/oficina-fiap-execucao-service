package br.com.fiap.soat15.execucaoservice.application;

public class DiagnosticoNaoEncontradoException extends RuntimeException {
    public DiagnosticoNaoEncontradoException(String osId) {
        super("Diagnóstico não encontrado para a OS " + osId);
    }
}
