# ADR-P7 — Reforços do conteúdo das aulas: isolamento na Saga e Circuit Breaker

- **Estado:** Aceita, implementação parcial
- **Data da decisão:** 15/10/2026 (kickoff da Fase 4)
- **Consolidação:** Guilherme, 08/11/2026
- **Escopo:** Billing Service (isolamento) e chamadas síncronas entre serviços (Circuit Breaker)

## Contexto

Antes do kickoff, o grupo revisou as premissas P1 a P6 contra o material de aula (Microsserviços I e II, Padrão SAGA). Nenhuma decisão precisou mudar: o curso confirma o SQS como alternativa tão válida quanto RabbitMQ ou Kafka, e aceita um orquestrador de Saga implementado sem framework dedicado. Mas o material traz dois cuidados que o desenho inicial não tratava:

1. **A Saga não tem isolamento entre os passos.** Cada serviço confirma sua transação local, e outro processo pode ler ou alterar o mesmo dado no meio do caminho. Por exemplo, o cliente aprovando o orçamento enquanto o sistema o reprocessa por timeout.
2. **Chamadas síncronas propagam falhas.** Se um serviço chamado por HTTP fica lento ou cai, quem depende dele pode travar junto.

## Decisão

- **Contramedida de isolamento no Billing (Pessimistic View ou Reread Value):** na etapa de orçamento e pagamento, bloquear o registro (bloqueio pessimista ou otimista) ou reler o valor antes de agir, para evitar condição de corrida.
- **Circuit Breaker nas chamadas REST entre serviços** (por exemplo, com Resilience4j), para que a falha de um serviço não derrube em cascata quem depende dele. Vale para quem **faz** chamadas síncronas.

## Alternativas consideradas

### Sem contramedida de isolamento

Mais simples, mas deixa espaço para cobrar ou aprovar um orçamento que já mudou. Descartada: o risco é financeiro.

### Transação distribuída (2PC)

Daria isolamento real, mas não é suportada entre PostgreSQL, DynamoDB e SQS, e acopla os serviços. É justamente o problema que a Saga resolve. Descartada.

### Só timeout, sem Circuit Breaker

O timeout limita a espera de cada chamada, mas continua mandando requisições para um serviço que já está falhando. Descartada como solução completa. O timeout continua junto com o Circuit Breaker.

## Consequências

### Positivas

- Aprovação e pagamento de um orçamento não correm em paralelo sobre dados desatualizados.
- Falhas em chamadas síncronas ficam contidas em quem as faz.

### Negativas e riscos

- O bloqueio pessimista serializa operações no mesmo orçamento (aceitável pelo volume da oficina).
- O Circuit Breaker precisa de uma resposta alternativa (fallback) definida para cada chamada.

## Situação em 08/11/2026

| Serviço | Situação |
| --- | --- |
| **Billing** | Isolamento implementado: o orçamento é lido com `PESSIMISTIC_WRITE` (`SpringDataOrcamentoRepository`). O Resilience4j está nas dependências; o Circuit Breaker nas chamadas ao Mercado Pago ainda precisa ser confirmado pelo Gabriel Ramos. |
| **OS** | Resilience4j previsto no README para as chamadas síncronas (por exemplo, consulta ao catálogo do Execução). Em desenvolvimento. |
| **Execução** | **Não se aplica.** O Execução não faz nenhuma chamada síncrona a outro serviço: só recebe HTTP e conversa com os demais pelo SQS. Por isso o Resilience4j foi **retirado** do serviço (PR #19), em vez de ficar como dependência sem uso. A proteção do Execução contra falhas está no consumo do SQS: entrega reprocessável, idempotência por `operationId`, DLQ após 5 tentativas e outbox. |
