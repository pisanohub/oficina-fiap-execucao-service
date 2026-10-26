/**
 * Camada de dominio do Execucao Service.
 *
 * TODO: este servico vai absorver duas responsabilidades que hoje estao no monolito
 * (pacote domain.entity do projeto principal):
 *  - Catalogo de servicos (equivalente a entidade "Servico" do monolito).
 *  - Estoque de pecas (equivalente a entidade "ItemEstoque" do monolito).
 *
 * Alem disso, este servico passa a manter a fila de execucao/diagnostico da ordem de
 * servico (estados que hoje ficam embutidos em StatusOS no monolito), avisando o OS
 * Service quando a execucao for concluida.
 *
 * Persistencia via DynamoDB (NoSQL) usando AWS SDK v2 Enhanced Client - sem JPA/banco
 * relacional neste servico. Nenhuma entidade de dominio real foi criada ainda; isso fica
 * para quem assumir a implementacao, seguindo o planejamento em
 * documentacao/divisao-tarefas-fase4.md no repositorio principal do grupo.
 */
package br.com.fiap.soat15.execucaoservice.domain;
