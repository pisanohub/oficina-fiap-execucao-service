package br.com.fiap.soat15.execucaoservice.contract;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

final class EnvelopeValidacao {
    private EnvelopeValidacao() {}

    static boolean uuidValido(String valor) {
        try {
            return UUID.fromString(valor) != null;
        } catch (IllegalArgumentException invalido) {
            return false;
        }
    }

    static boolean instanteValido(String valor) {
        try {
            return Instant.parse(valor) != null;
        } catch (DateTimeParseException invalido) {
            return false;
        }
    }
}
