package br.com.fiap.soat15.execucaoservice.adapter.in.web;

import br.com.fiap.soat15.execucaoservice.application.DiagnosticoNaoEncontradoException;
import br.com.fiap.soat15.execucaoservice.application.ItemCatalogoExistenteException;
import br.com.fiap.soat15.execucaoservice.application.VersaoDiagnosticoConflitanteException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ExecucaoExceptionHandler {
    @ExceptionHandler(DiagnosticoNaoEncontradoException.class)
    ProblemDetail naoEncontrado(DiagnosticoNaoEncontradoException exception) {
        return problema(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({VersaoDiagnosticoConflitanteException.class, ItemCatalogoExistenteException.class})
    ProblemDetail conflito(RuntimeException exception) {
        return problema(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalido(IllegalArgumentException exception) {
        return problema(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    private ProblemDetail problema(HttpStatus status, String detalhe) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setDetail(detalhe);
        return problem;
    }
}
