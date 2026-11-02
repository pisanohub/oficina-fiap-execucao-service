package br.com.fiap.soat15.execucaoservice.application;

public class VersaoDiagnosticoConflitanteException extends RuntimeException {
    public VersaoDiagnosticoConflitanteException(String osId) {
        super("Versão do diagnóstico desatualizada para a OS " + osId);
    }
}
