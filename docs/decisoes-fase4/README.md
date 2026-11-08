# ADRs da Fase 4 - Oficina FIAP

Índice das decisões de arquitetura da Fase 4, uma por premissa validada no kickoff de 15/10/2026 (P1 a P7). Segue o mesmo formato da Fase 3 ([`../decisoes`](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes/README.md)): contexto, decisão, alternativas e consequências.

Consolidação: **Guilherme**, 08/11/2026. Cada ADR tem um autor responsável, que revisa e ajusta o próprio documento. A consolidação reúne os ADRs, confere com o que foi implementado nos repositórios e registra a situação atual.

Visão geral da solução: [diagrama geral da arquitetura](../arquitetura-geral-fase4.md).

> Cópia dos documentos do repositório do grupo ([`oficina_fiap/documentacao/decisoes-fase4`](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes-fase4/README.md)), que é a fonte principal. Os ADRs P1, P6 e P7 estão aqui; os ADRs P2 a P5 são de outros autores e ficam só no `oficina_fiap` (links abaixo).

## Estado consolidado

| ADR | Assunto | Autor responsável | Estado |
| --- | --- | --- | --- |
| [ADR-P1](ADR-P1-tres-microsservicos.md) | Divisão em três microsserviços (OS, Billing, Execução) | Grupo (consolidado pelo Guilherme) | Aceita e implementada |
| [ADR-P2](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes-fase4/ADR-P2-bancos-de-dados.md) | Banco próprio por serviço: RDS PostgreSQL compartilhado (OS e Billing) e DynamoDB (Execução) | Pedro | Aceita; RDS e tabela DynamoDB criados |
| [ADR-P3](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes-fase4/ADR-P3-mensageria-sqs.md) | Mensageria com Amazon SQS, DLQ e consumidores idempotentes | Pedro | Aceita; 6 filas e 6 DLQs criadas |
| [ADR-P4](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes-fase4/ADR-P4-saga-orquestrada.md) | Saga orquestrada dentro do OS Service | Gabriel Souza | Proposta, em validação pelo autor |
| [ADR-P5](https://github.com/pisanohub/oficina_fiap/blob/main/documentacao/decisoes-fase4/ADR-P5-capacidade-cluster.md) | Capacidade do cluster: EC2 `t3.large` | Pedro | Aceita e implementada |
| [ADR-P6](ADR-P6-continuidade-fases-anteriores.md) | Continuidade das decisões das fases anteriores | Grupo (consolidado pelo Guilherme) | Aceita, implementação parcial |
| [ADR-P7](ADR-P7-reforcos-das-aulas.md) | Isolamento na Saga (Billing) e Circuit Breaker nas chamadas síncronas | Grupo (consolidado pelo Guilherme) | Aceita, implementação parcial |

## O que a implementação já confirmou

Os ADRs de cada autor seguem como foram escritos. Abaixo, o que já foi verificado nos repositórios e ainda não está registrado neles.

### ADR-P2 — Bancos

- A tabela DynamoDB `execucao_service` foi criada pelo Pedro, com chave `pk`/`sk` em tabela única, conferida contra o código do Execução (sem índice secundário).
- Falta confirmar na prática que a `LabRole` permite leitura e escrita no DynamoDB a partir do pod (só dá para testar com o Execução ligado às filas no cluster).

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
- Falta confirmar que a `LabRole` permite publicar e consumir nas filas a partir dos pods.

### ADR-P4 — Saga

- Os contratos de mensagens do Execução (`AgendarExecucao`, `CancelarExecucao` e os eventos `ExecucaoAgendada`, `ExecucaoRejeitada`, `ExecucaoIniciada`, `ExecucaoConcluida`, `ExecucaoCancelada`, `CancelamentoExecucaoRecusado`) estão no [guia de integração do Execução](https://github.com/pisanohub/oficina-fiap-execucao-service/blob/main/docs/contrato-provisorio-saga.md).
- O Billing já alinhou a geração do orçamento ao snapshot de preços do OS (PR #9 do Billing).
- O comando `EnviarParaExecucao` do event storming foi implementado como `AgendarExecucao`.

### ADR-P5 — Capacidade

- EC2 em `t3.large` desde a Semana 1 e Elastic IP fixo (`eip.tf`, PR #8 do `infra-k8s`). Pode ser marcado como implementado no próprio ADR.

## Pendências para fechar os ADRs

| Item | Responsável |
| --- | --- |
| Validar e assumir o ADR-P4 (está como proposta) | Gabriel Souza |
| Preencher o "Plano de verificação" do P2 e do P3 com o teste real da `LabRole` | Pedro |
| Marcar o ADR-P5 como implementado | Pedro |
| Confirmar o Circuit Breaker nas chamadas ao Mercado Pago (P7) | Gabriel Ramos |
| Definir rotas do API Gateway para os serviços novos e autenticação entre serviços (P6) | Gabriel Ramos, Pedro e Guilherme |
| Branch protection com `build-test` obrigatório nos três repositórios (P6) | Pedro |
