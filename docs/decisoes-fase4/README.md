# ADRs da Fase 4 - Oficina FIAP

Índice das decisões de arquitetura da Fase 4, uma por premissa validada no kickoff de 15/10/2026 (P1 a P7). Segue o mesmo formato da Fase 3 ([`../decisoes`](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes/README.md)): contexto, decisão, alternativas e consequências.

Consolidação: **Guilherme**, 16/11/2026. Cada ADR tem um autor responsável. A consolidação reúne os ADRs e confere com o que foi implementado nos repositórios.

Visão geral da solução: [diagrama geral da arquitetura](../arquitetura-geral-fase4.md).

> Cópia dos documentos do repositório do grupo ([`oficina_fiap/documentacao/decisoes-fase4`](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes-fase4/README.md)), que é a fonte principal. Os ADRs P1, P6 e P7 estão aqui; os ADRs P2 a P5 são de outros autores e ficam só no `oficina_fiap` (links abaixo).

## Estado consolidado

| ADR | Assunto | Autor responsável | Estado |
| --- | --- | --- | --- |
| [ADR-P1](ADR-P1-tres-microsservicos.md) | Divisão em três microsserviços (OS, Billing, Execução) | Grupo (consolidado pelo Guilherme) | Aceita e implementada |
| [ADR-P2](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes-fase4/ADR-P2-bancos-de-dados.md) | Banco próprio por serviço: RDS PostgreSQL compartilhado (OS e Billing) e DynamoDB (Execução) | Pedro | Aceita e implementada (plano de verificação fechado) |
| [ADR-P3](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes-fase4/ADR-P3-mensageria-sqs.md) | Mensageria com Amazon SQS, DLQ e consumidores idempotentes | Pedro | Aceita e implementada (plano de verificação fechado) |
| [ADR-P4](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes-fase4/ADR-P4-saga-orquestrada.md) | Saga orquestrada dentro do OS Service | Gabriel Souza | Implementada (orquestrador no OS Service) |
| [ADR-P5](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes-fase4/ADR-P5-capacidade-cluster.md) | Capacidade do cluster: EC2 `t3.large` | Pedro | Aceita e implementada (plano de verificação fechado) |
| [ADR-P6](ADR-P6-continuidade-fases-anteriores.md) | Continuidade das decisões das fases anteriores | Grupo (consolidado pelo Guilherme) | Aceita, implementação parcial |
| [ADR-P7](ADR-P7-reforcos-das-aulas.md) | Isolamento na Saga (Billing) e Circuit Breaker nas chamadas síncronas | Grupo (consolidado pelo Guilherme) | Aceita, implementação parcial |

## O que a implementação já confirmou

O que a implementação confirmou em cada decisão:

### ADR-P2 — Bancos

- A tabela DynamoDB `execucao_service` foi criada pelo Pedro, com chave `pk`/`sk` em tabela única, conferida contra o código do Execução (sem índice secundário).
- Confirmado em 09/11/2026 que a `LabRole` permite leitura e escrita no DynamoDB a partir do pod: o Execução roda no cluster gravando e consultando a tabela, sem erro de permissão.

### ADR-P3 — Mensageria

- Provisionamento no `oficina-fiap-infra-k8s` (`sqs.tf`), aplicado em 02/11/2026. Configuração real: visibilidade de 30 s e `maxReceiveCount` 5.
- Filas criadas (cada uma com DLQ):

| Fila | Direção |
| --- | --- |
| `gerar-orcamento` | OS → Billing |
| `orcamento-respondido` | Billing → OS |
| `processar-pagamento` | OS → Billing |
| `pagamento-confirmado` | Billing → OS |
| `execucao-comandos` | OS → Execução |
| `execucao-resultados` | Execução → OS |

- O Execução já implementa o que o ADR pede: consumidor idempotente (deduplicação por `operationId`), DLQ com reentrega imediata para falhas permanentes, e publicação por **outbox transacional** (o evento é gravado na mesma transação da mudança de estado e publicado depois, com `messageId` determinístico).
- Confirmado em 09/11/2026 que a `LabRole` permite publicar e consumir nas filas a partir dos pods: o Execução roda no cluster com o consumidor, o publicador e as métricas das filas ligados (PR #27 do Execução).

### ADR-P4 — Saga

- Os contratos de mensagens do Execução (`AgendarExecucao`, `CancelarExecucao` e os eventos `ExecucaoAgendada`, `ExecucaoRejeitada`, `ExecucaoIniciada`, `ExecucaoConcluida`, `ExecucaoCancelada`, `CancelamentoExecucaoRecusado`) estão no [guia de integração do Execução](https://github.com/pisanohub/oficina-fiap-execucao-service/blob/main/docs/guia-de-integracao-saga.md).
- O Billing já alinhou a geração do orçamento ao snapshot de preços do OS (PR #9 do Billing).
- O comando `EnviarParaExecucao` do event storming foi implementado como `AgendarExecucao`.
- A OS passa para `EM_EXECUCAO` ao enviar o `AgendarExecucao`, e o `ENTREGUE` é uma ação explícita do operador (decisões de 07/11/2026, no event storming).

### ADR-P5 — Capacidade

- EC2 em `t3.large` desde a Semana 1 e Elastic IP fixo (`eip.tf`, PR #8 do `infra-k8s`).

## Verificações concluídas

Em 15/11/2026: plano de verificação do P2 e do P3 preenchido com o teste real da `LabRole` (reconfirmado após recriar o cluster do zero), ADR-P5 marcado como implementado, e branch protection com `build-test` obrigatório já confirmada nos três repositórios de serviço.
