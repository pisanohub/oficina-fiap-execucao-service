package br.com.fiap.soat15.execucaoservice.adapter.in.web;

import br.com.fiap.soat15.execucaoservice.domain.model.ExecucaoTrabalho;
import br.com.fiap.soat15.execucaoservice.application.ExecucaoService;
import br.com.fiap.soat15.execucaoservice.domain.model.Cotacao;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ExecucaoController {
    private final ExecucaoService service;

    public ExecucaoController(ExecucaoService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/execucoes")
    public List<ExecucaoTrabalho> listarExecucoes() {
        return service.listarExecucoes();
    }

    @GetMapping("/api/v1/execucoes/{osId}")
    public ExecucaoTrabalho consultarExecucao(@PathVariable String osId) {
        return service.consultarExecucao(osId);
    }

    @PostMapping("/api/v1/execucoes/{osId}/inicio")
    public ExecucaoTrabalho iniciarExecucao(@PathVariable String osId) {
        return service.iniciarExecucao(osId);
    }

    @PostMapping("/api/v1/execucoes/{osId}/conclusao")
    public ExecucaoTrabalho concluirExecucao(@PathVariable String osId) {
        return service.concluirExecucao(osId);
    }

    @PostMapping("/api/v1/catalogo")
    public ResponseEntity<Void> salvarItem(@RequestBody ItemCatalogo item) {
        service.salvarItemCatalogo(item);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/diagnosticos/{osId}/inicio")
    public Diagnostico iniciar(@PathVariable String osId) {
        return service.iniciarDiagnostico(osId);
    }

    @PutMapping("/api/v1/diagnosticos/{osId}/itens")
    public Diagnostico atualizar(@PathVariable String osId, @RequestBody AtualizarItensRequest request) {
        return service.atualizarItens(osId, request.versaoEsperada(), request.itens());
    }

    @GetMapping("/api/v1/diagnosticos/{osId}")
    public Diagnostico consultar(@PathVariable String osId) {
        return service.consultarDiagnostico(osId);
    }

    @PostMapping("/api/v1/cotacoes")
    public Cotacao cotar(@RequestBody CotacaoRequest request) {
        return service.cotar(request.itens());
    }

    public record AtualizarItensRequest(long versaoEsperada, List<ItemSolicitado> itens) {
    }

    public record CotacaoRequest(List<ItemSolicitado> itens) {
    }
}
