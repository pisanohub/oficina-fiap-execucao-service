# Contratos provisórios de Execução - revisão 1

**Status:** topologia de quatro filas aprovada pelo grupo; payloads e rotas ainda sujeitos à validação com Gabriel Souza, Gabriel Ramos e Pedro. Atualizado após a sequência informada por Pedro em 02/11/2026. O arquivo `event-storming-saga-v1.md` atualizado ainda não apareceu no checkout de `oficina_fiap/main` após `git pull`; esta revisão usa a mensagem do Pedro como referência.

## Sequência de negócio

1. OS é aberta no **OS Service**, sem disparar orçamento.
2. Mecânico inicia diagnóstico e registra serviços/peças no **Execução Service**. O OS registra/projeta `EM_DIAGNOSTICO`.
3. A ação explícita **solicitar orçamento** ocorre no **OS Service**. O OS obtém os itens e a versão do diagnóstico por contrato interno com Execução e publica `GerarOrcamento` para Billing.
4. Após aprovação e pagamento confirmado, o OS publica `AgendarExecucao` para Execução. Só então Execução reserva peças e cria a fila de trabalho.

O Execução Service não altera diretamente o status ou o banco do OS. A forma de autenticar o operador e proteger as rotas internas será fechada com o grupo.

## HTTP de diagnóstico e cotação

As **mesmas rotas e o mesmo código** são usados localmente e no ambiente do grupo. Apenas o endpoint do DynamoDB/SQS, credenciais e porta mudam por configuração. Os caminhos ainda serão validados com o grupo:

| Operação | Rota implementada |
| --- | --- |
| Cadastrar item de catálogo/estoque | `POST /api/v1/catalogo` |
| Iniciar diagnóstico | `POST /api/v1/diagnosticos/{osId}/inicio` |
| Substituir itens com versão esperada | `PUT /api/v1/diagnosticos/{osId}/itens` |
| Consultar diagnóstico como operador | `GET /api/v1/diagnosticos/{osId}` |
| OS lê diagnóstico e versão para solicitar orçamento | `GET /internal/v1/diagnosticos/{osId}` |
| Billing consulta preços do catálogo | `POST /internal/v1/cotacoes` |

Exemplo de atualização (a primeira versão após iniciar é `1`):

```json
{
  "versaoEsperada": 1,
  "itens": [
    { "tipo": "SERVICO", "itemId": "troca-oleo", "quantidade": 1 },
    { "tipo": "PECA", "itemId": "filtro-oleo", "quantidade": 1 }
  ]
}
```

O catálogo e o diagnóstico pertencem à Execução. Billing usa a cotação para congelar os preços no orçamento. O diagnóstico guarda IDs/quantidades e uma versão; ele não guarda preços, porque a cotação é feita no momento de gerar orçamento. Uma mudança de itens após orçamento exige uma nova versão/novo orçamento e nova aprovação, a alinhar com o grupo. Antes de expor essas rotas pelo Gateway, falta aplicar o contrato de autenticação/autorização do operador e identidade de serviço para as chamadas internas.

## SQS após pagamento

O grupo escolheu quatro filas principais, com uma DLQ para cada uma:

| Fila principal | Publicador | Consumidor | DLQ |
| --- | --- | --- | --- |
| `billing-comandos` | OS | Billing | `billing-comandos-dlq` |
| `billing-resultados` | Billing | OS | `billing-resultados-dlq` |
| `execucao-comandos` | OS | Execução | `execucao-comandos-dlq` |
| `execucao-resultados` | Execução | OS | `execucao-resultados-dlq` |

SQS transporta comandos/resultados; a fila de trabalho é persistida no DynamoDB. O OS consome as duas filas de resultados. Os nomes acima são a convenção de implementação a validar contra o Terraform do Pedro antes do deploy.

Exemplo do comando a receber em `execucao-comandos`:

```json
{
  "schemaVersion": 1,
  "messageId": "02539ee2-c754-4bb7-b178-4b328de9dbd5",
  "operationId": "agendar-os-123-orcamento-v2",
  "type": "AgendarExecucao",
  "sagaId": "saga-123",
  "osId": "123",
  "causationId": "evento-pagamento-123",
  "occurredAt": "2026-11-02T14:00:00Z",
  "payload": {
    "orcamentoId": "orc-123",
    "versao": 2,
    "pagamentoId": "pag-123",
    "itens": [
      { "tipo": "SERVICO", "itemId": "troca-oleo", "quantidade": 1 },
      { "tipo": "PECA", "itemId": "filtro-oleo", "quantidade": 1 }
    ]
  }
}
```

`messageId` identifica o envelope; `operationId` é a identidade estável da operação de negócio para deduplicação; `sagaId` e `osId` correlacionam as etapas. `occurredAt` é UTC. O código valida `AgendarExecucaoCommand` e já oferece `ExecucaoService.agendarExecucao`: uma única transação DynamoDB reduz o estoque das peças, cria a execução `AGENDADA`, registra `operationId` para deduplicação e grava `ExecucaoAgendada` na outbox. A execução usa `pk=OS#{osId}, sk=EXECUCAO`; a operação usa `pk=OPERACAO#{operationId}, sk=AGENDAMENTO`; a outbox usa `pk=OUTBOX#{operationId}, sk=RESULTADO`. A mesma `operationId` retorna o agendamento existente; uma segunda operação para a mesma OS não cria outra execução. Estoque insuficiente ou conflito cancelam toda a transação. **O consumidor SQS e o publicador da outbox ainda não estão ligados**. Antes de consumir comandos, é preciso publicar e confirmar a outbox com retry seguro e definir a resposta de rejeição para estoque insuficiente. Os itens de serviço permanecem no registro de execução; só peças reduzem estoque.

Resultados previstos em `execucao-resultados`: `ExecucaoAgendada`, `ExecucaoRejeitada`, `ExecucaoIniciada`, `ExecucaoConcluida`, `ExecucaoCancelada` e `CancelamentoExecucaoRecusado`. Payloads e tratamento de compensações permanecem para o acordo com o OS Service.
