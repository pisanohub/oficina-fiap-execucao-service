package br.com.fiap.soat15.execucaoservice.config;

import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
public class OpenApiModelosConfig {
    private static final String TIPO_ITEM = "Tipo do item: PECA (tem estoque) ou SERVICO (não tem estoque).";
    private static final String ITEM_ID = "Identificador do item no catálogo.";
    private static final String INSTANTE_EXEMPLO = "2026-11-02T14:00:00Z";

    @Bean
    OpenApiCustomizer documentacaoDosModelos() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();

            modelo(schemas, "ItemCatalogo", "Peça ou serviço do catálogo.")
                    .campo("tipo", TIPO_ITEM, "PECA").obrigatorio()
                    .campo("itemId", "Identificador textual, único por tipo.", "filtro-oleo").obrigatorio()
                    .campo("nome", "Nome do item.", "Filtro de óleo").obrigatorio()
                    .campo("descricao", "Descrição opcional, até 300 caracteres.", "Filtro de óleo original")
                    .campo("precoUnitario", "Preço unitário em reais, maior ou igual a zero e com até 2 casas decimais.",
                            new BigDecimal("45.50")).obrigatorio()
                    .campo("ativo", "Se o item pode ser usado no diagnóstico, na cotação e no agendamento.", true)
                    .campo("quantidadeEstoque", "Estoque atual. Obrigatório (maior ou igual a zero) para PECA e nulo para SERVICO.", 10)
                    .campo("tempoEstimadoMinutos", "Tempo estimado em minutos (maior que zero). Opcional e só para SERVICO.", 45);

            modelo(schemas, "ItemSolicitado", "Item do diagnóstico, da cotação ou da execução, sem preço.")
                    .campo("tipo", TIPO_ITEM, "PECA").obrigatorio()
                    .campo("itemId", ITEM_ID, "filtro-oleo").obrigatorio()
                    .campo("quantidade", "Quantidade, maior que zero.", 1).obrigatorio();

            modelo(schemas, "ItemCotado", "Item cotado com o preço atual do catálogo.")
                    .campo("tipo", TIPO_ITEM, "PECA")
                    .campo("itemId", ITEM_ID, "filtro-oleo")
                    .campo("nome", "Nome atual do item no catálogo.", "Filtro de óleo")
                    .campo("quantidade", "Quantidade solicitada.", 2)
                    .campo("precoUnitario", "Preço unitário atual, em reais.", new BigDecimal("45.50"));

            modelo(schemas, "Cotacao", "Preços atuais dos itens. Não cria orçamento nem reserva estoque.")
                    .campo("moeda", "Moeda dos preços.", "BRL")
                    .campo("geradaEm", "Momento da consulta (UTC).", INSTANTE_EXEMPLO)
                    .campo("itens", "Itens cotados, na ordem enviada.", null);

            modelo(schemas, "Diagnostico", "Diagnóstico da OS: itens e quantidades, sem preço.")
                    .campo("osId", "Identificador da OS.", "123")
                    .campo("versao", "Revisão da lista de itens; cresce a cada alteração.", 2)
                    .campo("status", "Estado do diagnóstico. Hoje sempre EM_DIAGNOSTICO.", "EM_DIAGNOSTICO")
                    .valores("EM_DIAGNOSTICO")
                    .campo("itens", "Serviços e peças do diagnóstico.", null)
                    .campo("atualizadoEm", "Última alteração (UTC).", INSTANTE_EXEMPLO);

            modelo(schemas, "ExecucaoTrabalho", "Execução do trabalho de uma OS na fila do mecânico.")
                    .campo("osId", "Identificador da OS.", "123")
                    .campo("status", "Estado da execução.", "AGENDADA")
                    .valores("AGENDADA", "EM_EXECUCAO", "CONCLUIDA", "CANCELADA")
                    .campo("sagaId", "Identificador da Saga que agendou a execução.", "saga-123")
                    .campo("itens", "Serviços e peças a executar.", null)
                    .campo("agendadaEm", "Momento do agendamento (UTC).", INSTANTE_EXEMPLO)
                    .campo("iniciadaEm", "Início do trabalho (UTC); nulo enquanto AGENDADA.", "2026-11-02T15:00:00Z")
                    .campo("concluidaEm", "Conclusão do trabalho (UTC); nula até CONCLUIDA.", "2026-11-02T16:30:00Z");
        };
    }

    private static Modelo modelo(Map<String, Schema> schemas, String nome, String descricao) {
        Schema<?> schema = schemas.get(nome);
        if (schema != null) {
            schema.setDescription(descricao);
        }
        return new Modelo(schema);
    }

    private static final class Modelo {
        private final Schema<?> schema;
        private Schema<?> atual;
        private String nomeAtual;

        private Modelo(Schema<?> schema) {
            this.schema = schema;
        }

        Modelo campo(String nome, String descricao, Object exemplo) {
            nomeAtual = nome;
            atual = schema == null || schema.getProperties() == null ? null : schema.getProperties().get(nome);
            if (atual != null) {
                atual.setDescription(descricao);
                if (exemplo != null) {
                    atual.setExample(exemplo);
                }
            }
            return this;
        }

        Modelo obrigatorio() {
            if (atual != null) {
                List<String> obrigatorios = schema.getRequired() == null ? new ArrayList<>() : new ArrayList<>(schema.getRequired());
                if (!obrigatorios.contains(nomeAtual)) {
                    obrigatorios.add(nomeAtual);
                }
                schema.setRequired(obrigatorios);
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        Modelo valores(String... permitidos) {
            if (atual != null) {
                ((Schema<String>) atual).setEnum(List.of(permitidos));
            }
            return this;
        }
    }
}
