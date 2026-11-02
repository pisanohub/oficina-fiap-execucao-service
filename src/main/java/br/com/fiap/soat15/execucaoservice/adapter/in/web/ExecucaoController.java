package br.com.fiap.soat15.execucaoservice.adapter.in.web;

import br.com.fiap.soat15.execucaoservice.domain.model.ExecucaoTrabalho;
import br.com.fiap.soat15.execucaoservice.application.ExecucaoService;
import br.com.fiap.soat15.execucaoservice.domain.model.Cotacao;
import br.com.fiap.soat15.execucaoservice.domain.model.Diagnostico;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemCatalogo;
import br.com.fiap.soat15.execucaoservice.domain.model.ItemSolicitado;
import br.com.fiap.soat15.execucaoservice.domain.model.TipoItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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

    @GetMapping("/api/v1/catalogo")
    public List<ItemCatalogo> listarCatalogo(@RequestParam(required = false) TipoItem tipo,
                                             @RequestParam(required = false) Boolean ativo,
                                             @RequestParam(required = false) String nome) {
        return service.listarCatalogo(tipo, ativo, nome);
    }

    @GetMapping("/api/v1/catalogo/{tipo}/{itemId}")
    public ItemCatalogo consultarItem(@PathVariable TipoItem tipo, @PathVariable String itemId) {
        return service.consultarItemCatalogo(tipo, itemId);
    }

    @PutMapping("/api/v1/catalogo/{tipo}/{itemId}")
    public ItemCatalogo atualizarItem(@PathVariable TipoItem tipo, @PathVariable String itemId,
                                      @RequestBody AtualizarItemCatalogoRequest request) {
        return service.atualizarItemCatalogo(tipo, itemId, request.nome(), request.descricao(),
                request.precoUnitario(), request.ativo(), request.tempoEstimadoMinutos());
    }

    @DeleteMapping("/api/v1/catalogo/{tipo}/{itemId}")
    public ResponseEntity<Void> inativarItem(@PathVariable TipoItem tipo, @PathVariable String itemId) {
        service.inativarItemCatalogo(tipo, itemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/catalogo/{tipo}/{itemId}/estoque/reposicao")
    public ItemCatalogo reporEstoque(@PathVariable TipoItem tipo, @PathVariable String itemId,
                                     @RequestBody ReporEstoqueRequest request) {
        return service.reporEstoque(tipo, itemId, request.quantidade());
    }

    @PutMapping("/api/v1/catalogo/{tipo}/{itemId}/estoque")
    public ItemCatalogo ajustarEstoque(@PathVariable TipoItem tipo, @PathVariable String itemId,
                                       @RequestBody AjustarEstoqueRequest request) {
        return service.ajustarEstoque(tipo, itemId, request.quantidadeEstoque());
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

    public record AtualizarItemCatalogoRequest(String nome, String descricao, BigDecimal precoUnitario, Boolean ativo,
                                               Integer tempoEstimadoMinutos) {
    }

    public record ReporEstoqueRequest(int quantidade) {
    }

    public record AjustarEstoqueRequest(Integer quantidadeEstoque) {
    }

    public record AtualizarItensRequest(long versaoEsperada, List<ItemSolicitado> itens) {
    }

    public record CotacaoRequest(List<ItemSolicitado> itens) {
    }
}
