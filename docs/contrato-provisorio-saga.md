# Contratos provisórios de Execução - revisão 1

**Status:** topologia de quatro filas aprovada pelo grupo; payloads e rotas ainda sujeitos à validação com Gabriel Souza, Gabriel Ramos e Pedro. Atualizado após a sequência informada por Pedro em 02/11/2026. O arquivo `event-storming-saga-v1.md` atualizado ainda não apareceu no checkout de `oficina_fiap/main` após `git pull`; esta revisão usa a mensagem do Pedro como referência.

## Sequência de negócio

1. OS é aberta no **OS Service**, sem disparar orçamento.
2. Mecânico inicia diagnóstico e registra serviços/peças no **Execução Service**. O OS registra/projeta `EM_DIAGNOSTICO`.
3. A ação explícita **solicitar orçamento** ocorre no **OS Service**. O OS obtém os itens e a versão do diagnóstico por contrato interno com Execução e publica `GerarOrcamento` para Billing.
4. Após aprovação e pagamento confirmado, o OS publica `AgendarExecucao` para Execução. Só então Execução reserva peças e cria a fila de trabalho.

O Execução Service não altera diretamente o status ou o banco do OS. A forma de autenticar o operador e autorizar o acesso dos serviços às rotas será fechada com o grupo.

## HTTP de diagnóstico e cotação

As **mesmas rotas e o mesmo código** são usados localmente e no ambiente do grupo. Apenas o endpoint do DynamoDB/SQS, credenciais e porta mudam por configuração. Os caminhos ainda serão validados com o grupo:

| Operação | Rota implementada |
| --- | --- |
| Cadastrar item de catálogo/estoque | `POST /api/v1/catalogo` |
| Iniciar diagnóstico | `POST /api/v1/diagnosticos/{osId}/inicio` |
| Substituir itens com versão esperada | `PUT /api/v1/diagnosticos/{osId}/itens` |
| Consultar diagnóstico como operador | `GET /api/v1/diagnosticos/{osId}` |
| OS lê diagnóstico e versão para solicitar orçamento | `GET /api/v1/diagnosticos/{osId}` |
| Billing consulta preços do catálogo | `POST /api/v1/cotacoes` |

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

O catálogo e o diagnóstico pertencem à Execução. Billing usa a cotação para congelar os preços no orçamento. O diagnóstico guarda IDs/quantidades e uma versão; ele não guarda preços, porque a cotação é feita no momento de gerar orçamento. Uma mudança de itens após orçamento exige uma nova versão/novo orçamento e nova aprovação, a alinhar com o grupo. Antes de expor essas rotas pelo Gateway, falta aplicar autenticação e autorização para operadores e serviços.

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

`messageId` identifica o envelope; `operationId` é a identidade estável da operação de negócio para deduplicação; `sagaId` e `osId` correlacionam as etapas. `occurredAt` é UTC. O código valida `AgendarExecucaoCommand` e já oferece `ExecucaoService.agendarExecucao`: uma única transação DynamoDB reduz o estoque das peças, cria a execução `AGENDADA`, registra `operationId` para deduplicação e grava `ExecucaoAgendada` na outbox. A execução usa `pk=OS#{osId}, sk=EXECUCAO`; a operação usa `pk=OPERACAO#{operationId}, sk=AGENDAMENTO`; a outbox usa `pk=OUTBOX#{operationId}, sk=RESULTADO`. A mesma `operationId` retorna o agendamento existente; uma segunda operação para a mesma OS não cria outra execução. Estoque insuficiente ou conflito cancelam toda a transação. O consumidor SQS e o publicador da outbox podem ser habilitados por configuração. Os itens de serviço permanecem no registro de execução; só peças reduzem estoque.

Eventos enviados por Execução para `execucao-resultados`:

| Evento | Status no payload | Situação |
| --- | --- | --- |
| `ExecucaoAgendada` | `AGENDADA` | Implementado nesta branch |
| `ExecucaoIniciada` | `EM_EXECUCAO` | Implementado nesta branch |
| `ExecucaoConcluida` | `CONCLUIDA` | Implementado nesta branch |
| `ExecucaoRejeitada` | Sem status; envia motivo | Implementado |

O status descreve a execução. O OS Service decide como atualizar a OS; essa regra e as compensações ainda precisam ser validadas com o grupo. Exemplos completos estão logo abaixo, em **Mensagens de integração**.

O publicador da outbox envia `ExecucaoAgendada` para `execucao-resultados` quando `OUTBOX_PUBLISHER_ENABLED=true` e `SQS_EXECUCAO_RESULTADOS_URL` aponta para a fila. A mensagem usa `messageId` determinístico por tipo e `operationId`; o payload de agendamento traz `status`, `orcamentoId`, `versao` e `pagamentoId`. Um lease de dois minutos impede que duas réplicas publiquem simultaneamente o mesmo registro. Se o envio falhar, o item continua pendente e é tentado de novo após o lease. Se o SQS aceitar a mensagem mas a confirmação no DynamoDB falhar, pode haver duplicata: o OS deve deduplicar por `operationId`. A busca atual usa `Scan` paginado na tabela; antes de aumentar o volume, devemos criar um índice de pendências para evitar varreduras frequentes.

Para estoque insuficiente confirmado, a operação e o evento `ExecucaoRejeitada` são registrados juntos. O payload contém `motivo: ESTOQUE_INSUFICIENTE`, `itemId`, `orcamentoId`, `versao` e `pagamentoId`. A repetição da mesma operação mantém a rejeição; falhas técnicas deixam o comando na fila. Peças ausentes ou inativas também não são classificadas como estoque insuficiente. O contrato deve ser validado com o OS Service.

## Mensagens de integração: quem envia e quem recebe

Cada JSON é o **corpo de uma mensagem SQS**. OS envia `AgendarExecucao` para `execucao-comandos`; Execução consome. Execução envia os eventos para `execucao-resultados`; OS consome. Essas filas transportam mensagens entre serviços: a fila de trabalho do mecânico é um registro persistido na Execução.

| Tipo | Direção | Situação |
| --- | --- | --- |
| `AgendarExecucao` | OS → Execução | Implementado, contrato provisório; exemplo completo na seção SQS acima |
| `ExecucaoAgendada` | Execução → OS | Implementado |
| `ExecucaoRejeitada` | Execução → OS | Implementado |
| `ExecucaoIniciada` | Execução → OS | Implementado nesta branch |
| `ExecucaoConcluida` | Execução → OS | Implementado nesta branch |

### `ExecucaoAgendada` — implementado

Enviado depois que execução e reserva das peças foram registradas. O campo `causationId` contém o `messageId` do comando recebido. O `messageId` do evento é determinístico a partir do tipo e da operação.

```json
{
  "schemaVersion": 1,
  "messageId": "aaa4c87e-ef3c-3d38-b566-c23381e44071",
  "operationId": "agendar-os-123-orcamento-v2",
  "type": "ExecucaoAgendada",
  "sagaId": "saga-123",
  "osId": "123",
  "causationId": "02539ee2-c754-4bb7-b178-4b328de9dbd5",
  "occurredAt": "2026-11-02T14:00:00Z",
  "payload": {"status":"AGENDADA","orcamentoId":"orc-123","versao":2,"pagamentoId":"pag-123"}
}
```

O `messageId` acima é ilustrativo; o valor real é calculado pelo publicador.

### `ExecucaoRejeitada` — implementado

Enviado após confirmar estoque insuficiente e gravar a rejeição com a outbox. Possui o mesmo envelope de `ExecucaoAgendada`, com `type` igual a `ExecucaoRejeitada` e o seguinte payload:

```json
{"orcamentoId":"orc-123","versao":2,"pagamentoId":"pag-123","motivo":"ESTOQUE_INSUFICIENTE","itemId":"filtro-oleo"}
```

Falhas técnicas, peça inexistente ou inativa não são classificadas como estoque insuficiente; a mensagem não é confirmada e fica sujeita a nova tentativa e à DLQ conforme configuração da infraestrutura.

### `ExecucaoIniciada` — implementado nesta branch

Quando o operador inicia a execução, o serviço publica o evento abaixo. O `operationId` e o `causationId` reais são gerados a partir da OS; os identificadores deste exemplo são ilustrativos.

```json
{
  "schemaVersion": 1,
  "messageId": "37e9ef0b-107a-3d36-83d1-5d9159d93d7d",
  "operationId": "iniciar-execucao-identificador-da-os",
  "type": "ExecucaoIniciada",
  "sagaId": "saga-123",
  "osId": "123",
  "causationId": "iniciar-execucao-identificador-da-os",
  "occurredAt": "2026-11-02T15:00:00Z",
  "payload": {
    "status": "EM_EXECUCAO",
    "iniciadaEm": "2026-11-02T15:00:00Z",
    "orcamentoId": "orc-123",
    "versao": 2,
    "pagamentoId": "pag-123"
  }
}
```

### `ExecucaoConcluida` — implementado nesta branch

Sugestão de estados internos da execução: `AGENDADA → EM_EXECUCAO → CONCLUIDA`. Após a ação do operador, o serviço publica `ExecucaoIniciada` ou `ExecucaoConcluida` em `execucao-resultados`.

Exemplo do corpo de `ExecucaoConcluida`; os identificadores são ilustrativos:

```json
{
  "schemaVersion": 1,
  "messageId": "d1e4ea61-4bc0-4cf9-90ef-4c86c8417f21",
  "operationId": "concluir-execucao-identificador-da-os",
  "type": "ExecucaoConcluida",
  "sagaId": "saga-123",
  "osId": "123",
  "causationId": "concluir-execucao-identificador-da-os",
  "occurredAt": "2026-11-02T17:30:00Z",
  "payload": {"status":"CONCLUIDA","iniciadaEm":"2026-11-02T15:00:00Z","concluidaEm":"2026-11-02T17:30:00Z","orcamentoId":"orc-123","versao":2,"pagamentoId":"pag-123"}
}
```

Os horários permitem medir a duração do trabalho, como no monólito. O `ExecucaoIniciada` implementado usa o mesmo envelope e inclui `iniciadaEm` e `status` no payload. Para início e conclusão, `operationId` e `causationId` são derivados deterministicamente da OS. Cada ação deve ter uma identidade própria estável em repetições; consumidores devem tolerar entrega duplicada.

**Ponto para decisão com OS e infraestrutura:** o desenho informado pelo Pedro coloca a OS em `EM_EXECUCAO` após confirmação do pagamento; no monólito esse estado registra o início do trabalho. Uma alternativa é a OS mudar para `EM_EXECUCAO` ao receber `ExecucaoIniciada`, mas o estado da OS entre pagamento e início precisa ser definido. Ainda falta o tratamento de cancelamento e compensação.

## Guia de integração HTTP

Esta seção descreve o comportamento do código desta branch. Endereços, autenticação e autorização para acesso externo ou entre serviços ainda devem ser definidos com o grupo. JSONs são exemplos; `osId` e `itemId` são strings.

### Cadastrar item de catálogo — implementado

`POST /api/v1/catalogo` cria um item. Resposta: `204 No Content`; item já existente: `409 Conflict`. O serviço não tem estoque (`quantidadeEstoque` deve ser nulo); a peça exige quantidade inteira não negativa. O preço aceita até duas casas decimais.

```json
{"tipo":"PECA","itemId":"filtro-oleo","nome":"Filtro de óleo","precoUnitario":45.50,"ativo":true,"quantidadeEstoque":10}
```

### Iniciar diagnóstico — implementado

`POST /api/v1/diagnosticos/{osId}/inicio` inicia o diagnóstico ou retorna o existente. Não exige corpo. Exemplo de resposta `200 OK`:

```json
{"osId":"123","versao":1,"status":"EM_DIAGNOSTICO","itens":[],"atualizadoEm":"2026-11-02T14:00:00Z"}
```

### Substituir itens do diagnóstico — implementado

`PUT /api/v1/diagnosticos/{osId}/itens` substitui a lista inteira, usando `versaoEsperada` para evitar que uma alteração sobrescreva outra. O exemplo de requisição está na seção HTTP acima. A resposta `200 OK` contém o diagnóstico atualizado, com nova versão. Diagnóstico inexistente retorna `404`; versão divergente, `409`; dados inválidos, `400`.

### Consultar diagnóstico — implementado

`GET /api/v1/diagnosticos/{osId}` é a rota prevista para operador e serviços autorizados. A resposta `200 OK` tem o formato do diagnóstico mostrado acima, incluindo os itens atuais; `404` indica diagnóstico inexistente.

### Consultar preços — implementado

`POST /api/v1/cotacoes` consulta os preços atuais dos itens ativos; não cria orçamento nem reserva estoque. Exemplo de requisição:

```json
{"itens":[{"tipo":"PECA","itemId":"filtro-oleo","quantidade":2}]}
```

Exemplo de resposta `200 OK`:

```json
{"moeda":"BRL","geradaEm":"2026-11-02T14:00:00Z","itens":[{"tipo":"PECA","itemId":"filtro-oleo","nome":"Filtro de óleo","quantidade":2,"precoUnitario":45.50}]}
```

Itens ausentes ou inativos e requisições inválidas podem retornar `400`. O Billing é responsável por formar e congelar o orçamento conforme o contrato a ser alinhado.

## Fila de trabalho — implementação nesta branch

- `GET /api/v1/execucoes`: lista execuções registradas. A consulta atual percorre a tabela DynamoDB inteira e não tem paginação HTTP; é uma implementação inicial para baixo volume.
- `GET /api/v1/execucoes/{osId}`: consulta uma execução; retorna `404` se não existir.
- `POST /api/v1/execucoes/{osId}/conclusao`: conclui uma execução `EM_EXECUCAO`, sem corpo; retorna `200` com o registro. Repetições retornam o mesmo resultado; execução inexistente retorna `404` e estado inválido, `409`.
- `POST /api/v1/execucoes/{osId}/inicio`: inicia uma execução `AGENDADA`, sem corpo. Retorna `200` com o registro; uma repetição com estado `EM_EXECUCAO` devolve o registro existente. Outros estados retornam `409`; execução inexistente, `404`.

Exemplo de resposta após início:

```json
{"osId":"123","status":"EM_EXECUCAO","sagaId":"saga-123","itens":[{"tipo":"PECA","itemId":"filtro-oleo","quantidade":1}],"agendadaEm":"2026-11-02T14:00:00Z","iniciadaEm":"2026-11-02T15:00:00Z","concluidaEm":null}
```

O início grava a transição e a outbox na mesma transação. O evento `ExecucaoIniciada` usa `operationId` e `causationId` determinísticos derivados da OS; seu payload contém `status: EM_EXECUCAO`, `iniciadaEm`, `orcamentoId`, `versao` e `pagamentoId`. O evento `ExecucaoAgendada` passa a conter `status: AGENDADA`; rejeições continuam informando o motivo, pois não há execução criada. O publicador pode reenviar mensagens e a ordem de entrega entre eventos não é garantida: o OS Service deve lidar com duplicatas e eventos fora de ordem. A conclusão também grava a mudança de estado e a outbox juntas. O contrato e a sincronização de status seguem sujeitos à validação do grupo.

## Cancelamento da execução — implementação nesta branch

O OS Service envia `CancelarExecucao` para `execucao-comandos`. A execução deve pertencer à mesma saga. Este contrato é provisório e precisa ser validado com o grupo.

```json
{
  "schemaVersion": 1,
  "messageId": "7b9c84b4-144f-4ad1-943e-c30bc8e2073b",
  "operationId": "cancelar-os-123",
  "type": "CancelarExecucao",
  "sagaId": "saga-123",
  "osId": "123",
  "causationId": "causa-do-cancelamento",
  "occurredAt": "2026-11-02T15:00:00Z"
}
```

A mesma `operationId` deve ser mantida nas tentativas da mesma solicitação. Em `AGENDADA`, o serviço devolve as peças, altera o estado para `CANCELADA` e registra o resultado na outbox na mesma transação. Em `EM_EXECUCAO` ou `CONCLUIDA`, não devolve peças e registra a recusa. Para uma execução já `CANCELADA`, outra operação recebe confirmação sem devolver o estoque novamente. Repetir a mesma operação não gera outra devolução ou outro registro de resultado.

O OS Service recebe um destes eventos em `execucao-resultados`, com o envelope habitual (`schemaVersion`, `messageId`, `operationId`, `type`, `sagaId`, `osId`, `causationId`, `occurredAt`):

- `ExecucaoCancelada`: payload `{"status":"CANCELADA","orcamentoId":"orc-123","versao":2,"pagamentoId":"pag-123"}`.
- `CancelamentoExecucaoRecusado`: payload `{"motivo":"ESTADO_NAO_PERMITE_CANCELAMENTO","orcamentoId":"orc-123","versao":2,"pagamentoId":"pag-123"}`.

O `causationId` da resposta é o `messageId` do comando. A outbox pode reenviar mensagens; o OS deve tratar duplicatas. Execução inexistente, saga divergente e falhas técnicas não geram confirmação: o comando fica sujeito a novas tentativas e à DLQ. O resultado desejado para execução inexistente permanece para alinhamento com o grupo. A ordem entre cancelamento e estorno é responsabilidade do OS Service e ainda precisa ser acordada.
