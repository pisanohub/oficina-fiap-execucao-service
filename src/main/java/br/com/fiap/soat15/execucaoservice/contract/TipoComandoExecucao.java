package br.com.fiap.soat15.execucaoservice.contract;

public enum TipoComandoExecucao {
    AGENDAR("AgendarExecucao"),
    CANCELAR("CancelarExecucao");

    private final String codigo;

    TipoComandoExecucao(String codigo) {
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }

    public static TipoComandoExecucao fromCodigo(String codigo) {
        for (TipoComandoExecucao tipo : values()) {
            if (tipo.codigo.equals(codigo)) return tipo;
        }
        throw new IllegalArgumentException("Tipo de comando desconhecido: " + codigo);
    }
}
