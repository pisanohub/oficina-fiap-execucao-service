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

## Como explicar a integração ao responsável pelo OS Service

**Esta é a proposta que o Execução Service entende hoje; ainda precisamos combiná-la com o Gabriel.** Não significa que o OS Service já esteja enviando essas mensagens. O serviço que envia a mensagem preenche o JSON e o coloca na fila SQS; o SQS transporta o corpo informado. Os campos de negócio não são preenchidos automaticamente pelo SQS.

Após o pagamento, a sequência esperada é:

1. O OS Service reúne os identificadores da OS, saga, orçamento e pagamento, e os itens aprovados.
2. O OS Service cria o comando `AgendarExecucao` e o envia para `execucao-comandos`, preenchendo os campos abaixo.
3. O Execução Service lê o comando, tenta agendar e registra um evento na outbox.
4. O Execução Service publica `ExecucaoAgendada` ou `ExecucaoRejeitada` em `execucao-resultados`; o OS Service lê a resposta e decide a próxima etapa.

| Campo recebido pelo Execução | Quem preenche no comando | Como explicar |
| --- | --- | --- |
| `schemaVersion` | OS Service | Enviar o número `1`: indica a versão do formato da mensagem que combinamos. É a versão do **contrato**, não da OS nem do orçamento. O código atual rejeita outro número. |
| `messageId` | OS Service | Criar um UUID válido para identificar a mensagem no JSON. Não confundir com o identificador que o próprio SQS fornece ao transportar a mensagem. |
| `operationId` | OS Service | Criar um identificador para a ação de agendar, por exemplo `agendar-os-123-orcamento-v2`. Se precisar reenviar a mesma ação, usar o mesmo valor. Outra ação, como cancelamento, precisa de outro identificador. |
| `type` | OS Service | Enviar exatamente `AgendarExecucao` para solicitar agendamento ou `CancelarExecucao` para solicitar cancelamento. |
| `sagaId` | Serviço que inicia ou coordena a saga, a combinar | Identificador que acompanha o mesmo fluxo entre serviços. O OS precisa incluí-lo no comando; no cancelamento deve corresponder ao da execução. |
| `osId` | OS Service | Identificador da OS, como texto. No exemplo, `"123"`. |
| `causationId` | OS Service, se disponível | Identifica o evento ou ação que motivou o comando, por exemplo a confirmação do pagamento. É opcional na validação atual. |
| `occurredAt` | OS Service | Data e hora de criação do comando em formato de instante, por exemplo `2026-11-02T14:00:00Z`. |
| `payload.orcamentoId` | OS Service, com dados do orçamento | Identificador do orçamento aprovado. |
| `payload.versao` | OS Service, com dados do orçamento | Versão do orçamento aprovado, número inteiro positivo. É independente de `schemaVersion`. |
| `payload.pagamentoId` | OS Service, com dados do pagamento | Identificador do pagamento confirmado. |
| `payload.itens` | OS Service, com dados aprovados | Lista de peças e serviços com `tipo`, `itemId` e `quantidade`; é a lista que Execução tentará agendar. |

A origem exata de cada dado entre OS e Billing, o identificador da saga e os nomes dos campos precisam ser confirmados com o grupo. O exemplo completo do comando aparece na seção seguinte.

**Exemplo de repetição:** se o OS reenviar o agendamento da OS `123` por não ter recebido a resposta, deve manter `operationId: agendar-os-123-orcamento-v2` e os mesmos dados da operação. Isso permite reconhecer o agendamento já processado e evita reservar as peças duas vezes. O SQS também pode entregar a mesma mensagem novamente; o consumidor precisa tolerar essa situação.

**Resposta:** o Execução Service cria outro JSON, por exemplo com `type: ExecucaoAgendada`. Ele mantém `sagaId`, `osId` e a `operationId` do agendamento, gera seu próprio `messageId` e coloca no `causationId` o `messageId` do comando recebido. Quando o mecânico inicia ou conclui por HTTP, Execução gera uma `operationId` própria para cada ação e publica os respectivos eventos. Os exemplos estão em **Mensagens de integração**.

Sugestão de mensagem para o Gabriel:

> Gabriel, no Execução Service implementei provisoriamente o recebimento de `AgendarExecucao` pela fila `execucao-comandos`. O OS enviaria os identificadores da saga, OS, orçamento e pagamento, além dos itens aprovados. Também enviaria `schemaVersion: 1`, um `messageId` UUID e um `operationId` que se mantém igual quando a mesma ação for repetida. Em resposta, Execução publicaria `ExecucaoAgendada` ou `ExecucaoRejeitada` em `execucao-resultados`. Podemos validar juntos esses campos e quem gera o `sagaId`? Os exemplos completos estão neste documento.

## SQS após pagamento

A topologia prevista tem quatro filas principais, com uma DLQ para cada uma. O Terraform deste serviço define as duas filas de Execução e suas DLQs; confirme com a equipe a posse dos recursos antes do deploy:

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
| `ExecucaoCancelada` | `CANCELADA` | Implementado nesta branch |
| `CancelamentoExecucaoRecusado` | Sem status; envia motivo | Implementado nesta branch |

O status descreve a execução. O OS Service decide como atualizar a OS; essa regra e as compensações ainda precisam ser validadas com o grupo. Exemplos completos estão logo abaixo, em **Mensagens de integração**.

O publicador da outbox envia `ExecucaoAgendada` para `execucao-resultados` quando `OUTBOX_PUBLISHER_ENABLED=true` e `SQS_EXECUCAO_RESULTADOS_URL` aponta para a fila. A mensagem usa `messageId` determinístico por tipo e `operationId`; o payload de agendamento traz `status`, `orcamentoId`, `versao` e `pagamentoId`. Um lease de dois minutos impede que duas réplicas publiquem simultaneamente o mesmo registro. Se o envio falhar, o item continua pendente e é tentado de novo após o lease. Se o SQS aceitar a mensagem mas a confirmação no DynamoDB falhar, pode haver duplicata: o OS deve deduplicar por `operationId`. A busca atual usa `Scan` paginado na tabela; antes de aumentar o volume, devemos criar um índice de pendências para evitar varreduras frequentes.

Para estoque insuficiente confirmado, a operação e o evento `ExecucaoRejeitada` são registrados juntos. O payload contém `motivo: ESTOQUE_INSUFICIENTE`, `itemId`, `orcamentoId`, `versao` e `pagamentoId`. A repetição da mesma operação mantém a rejeição; falhas técnicas deixam o comando na fila. Peças ausentes ou inativas também não são classificadas como estoque insuficiente. O contrato deve ser validado com o OS Service.

## Mensagens de integração: quem envia e quem recebe

Cada JSON é o **corpo de uma mensagem SQS**. OS envia `AgendarExecucao` e `CancelarExecucao` para `execucao-comandos`; Execução consome. Execução envia os eventos para `execucao-resultados`; OS consome. Essas filas transportam mensagens entre serviços: a fila de trabalho do mecânico é um registro persistido na Execução.

| Tipo | Direção | Situação |
| --- | --- | --- |
| `AgendarExecucao` | OS → Execução | Implementado, contrato provisório; exemplo completo na seção SQS acima |
| `ExecucaoAgendada` | Execução → OS | Implementado |
| `ExecucaoRejeitada` | Execução → OS | Implementado |
| `ExecucaoIniciada` | Execução → OS | Implementado nesta branch |
| `ExecucaoConcluida` | Execução → OS | Implementado nesta branch |
| `CancelarExecucao` | OS → Execução | Implementado nesta branch |
| `ExecucaoCancelada` | Execução → OS | Implementado nesta branch |
| `CancelamentoExecucaoRecusado` | Execução → OS | Implementado nesta branch |

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

**Ponto para decisão com OS e infraestrutura:** o desenho informado pelo Pedro coloca a OS em `EM_EXECUCAO` após confirmação do pagamento; no monólito esse estado registra o início do trabalho. Uma alternativa é a OS mudar para `EM_EXECUCAO` ao receber `ExecucaoIniciada`, mas o estado da OS entre pagamento e início precisa ser definido. O cancelamento antes do início está implementado; a compensação e a ordem do estorno ainda precisam ser alinhadas.

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


## Dicionário dos atributos das mensagens

Os campos abaixo formam o envelope dos comandos e eventos. Os tipos e validações indicados são os do código atual; os exemplos são provisórios e devem ser alinhados entre os serviços.

| Campo | Tipo | Significado e validação |
| --- | --- | --- |
| `schemaVersion` | inteiro | Versão do contrato. Os comandos recebidos exigem `1`. |
| `messageId` | string UUID | Identifica a mensagem. Nos comandos recebidos deve ser um UUID válido; nos eventos é gerado pelo Execução Service. |
| `operationId` | string | Identifica a operação de negócio. Deve ser preenchido e mantido na repetição da mesma operação. Use um valor distinto para cada ação e OS, inclusive entre tipos de comando. |
| `type` | string | Tipo da mensagem, por exemplo `AgendarExecucao` ou `ExecucaoConcluida`. O consumidor aceita os tipos de comando documentados. |
| `sagaId` | string | Identificador que correlaciona as etapas da saga. Obrigatório nos comandos; no cancelamento deve corresponder ao da execução. |
| `osId` | string | Identificador da OS. Obrigatório nos comandos; é a referência usada para localizar a execução. |
| `causationId` | string ou nulo | Identifica o que originou a mensagem. O código atual não o exige nos comandos. Para respostas a comandos, o evento usa o `messageId` recebido; nos eventos de início e conclusão usa a identidade gerada da ação. |
| `occurredAt` | string ISO-8601 | Horário da mensagem. Nos comandos deve ser um instante válido, por exemplo `2026-11-02T14:00:00Z`; os eventos são gerados em UTC. |
| `payload` | objeto | Dados específicos da mensagem. Obrigatório em `AgendarExecucao`; não é usado em `CancelarExecucao`. Os eventos incluem o objeto conforme seu tipo. |

### Campos de negócio

| Campo | Onde aparece | Significado |
| --- | --- | --- |
| `orcamentoId` | Agendamento e resultados | Identifica o orçamento aprovado; obrigatório no comando de agendamento. |
| `versao` | Agendamento e resultados | No agendamento e nos eventos, identifica a versão do orçamento e deve ser positiva no comando. Em diagnósticos, `versao` identifica a revisão dos itens; são conceitos distintos. |
| `pagamentoId` | Agendamento e resultados | Identifica o pagamento confirmado; obrigatório no agendamento. |
| `itens` | Diagnóstico, cotação e agendamento | Lista dos serviços e peças. Em comandos de agendamento são aceitos de 1 a 20 itens, sem duplicar tipo e identificador. |
| `tipo` | Item | `PECA` ou `SERVICO`. Somente peças participam da reserva e devolução de estoque. |
| `itemId` | Item ou evento de rejeição | Identificador do item no catálogo. Na rejeição, indica a peça identificada como indisponível. |
| `quantidade` | Item | Quantidade inteira positiva solicitada. |
| `status` | Diagnóstico, execução e eventos de sucesso | Estado registrado pelo Execução Service, por exemplo `AGENDADA`, `EM_EXECUCAO`, `CONCLUIDA` ou `CANCELADA`. O estado da OS é de responsabilidade do OS Service. |
| `motivo` | Eventos de recusa | Código para o motivo, como `ESTOQUE_INSUFICIENTE` ou `ESTADO_NAO_PERMITE_CANCELAMENTO`. |
| `iniciadaEm`, `concluidaEm` | Execução e eventos | Horários do início e da conclusão do trabalho; na resposta HTTP podem ser nulos antes da respectiva ação. |
| `agendadaEm` | Execução HTTP | Horário em que o agendamento foi registrado. |
| `versaoEsperada` | Atualização HTTP do diagnóstico | Versão lida antes da alteração; se divergente, a atualização retorna conflito. |
| `precoUnitario` | Catálogo e cotação | Preço por unidade; o catálogo aceita valor não negativo com até duas casas decimais. |
| `ativo` | Catálogo | Indica se o item está ativo para consulta e reserva. |
| `quantidadeEstoque` | Catálogo | Quantidade disponível de peça; obrigatória e não negativa para `PECA`, nula para `SERVICO`. |
| `nome` | Catálogo e cotação | Nome descritivo do item. |
| `moeda`, `geradaEm` | Cotação | Moeda e instante da consulta de preços. |
| `atualizadoEm` | Diagnóstico | Instante da última atualização do diagnóstico. |

## Exemplos de chamadas HTTP

Os exemplos assumem serviço na porta `8080` e que o DynamoDB configurado está disponível. Substitua o endereço pelo ambiente usado pelo grupo. As rotas ainda não possuem autenticação/autorização implementadas; o controle de acesso precisa ser definido antes da integração.

```bash
# Cadastrar peça
curl -i -X POST http://localhost:8080/api/v1/catalogo \
  -H 'Content-Type: application/json' \
  -d '{"tipo":"PECA","itemId":"filtro-oleo","nome":"Filtro de óleo","precoUnitario":45.50,"ativo":true,"quantidadeEstoque":10}'

# Iniciar diagnóstico e substituir seus itens
curl -i -X POST http://localhost:8080/api/v1/diagnosticos/123/inicio
curl -i -X PUT http://localhost:8080/api/v1/diagnosticos/123/itens \
  -H 'Content-Type: application/json' \
  -d '{"versaoEsperada":1,"itens":[{"tipo":"PECA","itemId":"filtro-oleo","quantidade":1}]}'

# Consultar diagnóstico e preços atuais
curl -i http://localhost:8080/api/v1/diagnosticos/123
curl -i -X POST http://localhost:8080/api/v1/cotacoes \
  -H 'Content-Type: application/json' \
  -d '{"itens":[{"tipo":"PECA","itemId":"filtro-oleo","quantidade":1}]}'

# Consultar a fila de trabalho; iniciar e concluir uma execução já agendada
curl -i http://localhost:8080/api/v1/execucoes
curl -i http://localhost:8080/api/v1/execucoes/123
curl -i -X POST http://localhost:8080/api/v1/execucoes/123/inicio
curl -i -X POST http://localhost:8080/api/v1/execucoes/123/conclusao
```

Essas chamadas HTTP não enviam o comando `AgendarExecucao`: ele chega pela fila SQS após o pagamento. Para que o exemplo de execução funcione, é necessário que o agendamento correspondente já tenha sido consumido.

### Respostas e erros HTTP

| Código | Uso no código atual |
| --- | --- |
| `200` | Consultas e operações de diagnóstico, início ou conclusão retornam os dados correspondentes. |
| `204` | Cadastro de item do catálogo aceito, sem corpo. |
| `400` | Dados de negócio inválidos, incluindo campos inválidos ou item de cotação ausente/inativo. |
| `404` | Diagnóstico ou execução não encontrados. |
| `409` | Item duplicado, versão de diagnóstico conflitante ou estado de execução incompatível. |

Erros tratados usam `ProblemDetail`, com informações como `status` e `detail`. Erros de infraestrutura e erros de desserialização podem produzir outras respostas; não devem ser interpretados como rejeição de negócio.

## Configuração necessária para as filas

| Variável | Finalidade | Padrão |
| --- | --- | --- |
| `AWS_REGION` | Região dos clientes AWS. | `us-east-1` |
| `DYNAMODB_TABLE_NAME` | Nome da tabela do serviço. | `execucao_service` |
| `DYNAMODB_ENDPOINT_URL` | Endpoint opcional para DynamoDB local. | vazio |
| `SQS_ENDPOINT_URL` | Endpoint opcional para SQS local. | vazio |
| `SQS_EXECUCAO_COMANDOS_URL` | URL da fila que Execução consome. | vazio |
| `SQS_CONSUMER_ENABLED` | Habilita a leitura dos comandos; requer a URL da fila. | `false` |
| `SQS_CONSUMER_POLL_DELAY_MS` | Intervalo entre chamadas do consumidor, em milissegundos. | `5000` |
| `SQS_EXECUCAO_RESULTADOS_URL` | URL da fila para publicar resultados. | vazio |
| `OUTBOX_PUBLISHER_ENABLED` | Habilita o publicador; requer a URL da fila de resultados. | `false` |
| `OUTBOX_POLL_DELAY_MS` | Intervalo entre leituras da outbox, em milissegundos. | `5000` |

A tabela, as filas, as DLQs, credenciais e permissões precisam existir no ambiente. Habilitar os componentes e definir URLs não cria esses recursos.

O Terraform propõe três recebimentos antes do envio à DLQ, configuráveis por `sqs_max_receive_count`. O valor, a existência das filas na AWS e a divisão de responsabilidade entre os repositórios devem ser confirmados com o Pedro.
