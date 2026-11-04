# Execucao Service

Microsservico de Execucao e Producao da Oficina FIAP (Tech Challenge Fase 4).

## Responsabilidade

Gerencia a fila de execucao da ordem de servico, atualiza o status durante diagnostico e
reparos, avisa o OS Service ao finalizar, e mantem o catalogo de servicos e o estoque de
pecas.

## Tecnologias

- Java 21
- Spring Boot 4.0.5
  - Spring Web, Validation, Actuator
- AWS SDK v2
  - SQS (fila de execucao/diagnostico)
  - DynamoDB Enhanced Client (catalogo de servicos e estoque de pecas)
- springdoc-openapi (Swagger UI)
- Lombok
- Maven + Maven Wrapper
- JaCoCo (cobertura de testes)

## Como rodar localmente

Pre-requisitos: Java 21.

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

No Windows (PowerShell/cmd):

```
mvnw.cmd clean verify
mvnw.cmd spring-boot:run
```

Variaveis de ambiente configuraveis (ver `src/main/resources/application.yaml`):

| Variavel | Default | Descricao |
|---|---|---|
| `AWS_REGION` | `us-east-1` | Regiao AWS usada pelos clientes SQS/DynamoDB |
| `DYNAMODB_ENDPOINT_URL` | vazio | Endpoint opcional do DynamoDB local |
| `SQS_ENDPOINT_URL` | vazio | Endpoint opcional do SQS local |
| `DYNAMODB_TABLE_NAME` | `execucao_service` | Nome da tabela DynamoDB |
| `SQS_EXECUCAO_RESULTADOS_URL` | vazio | URL da fila de resultados |
| `SQS_EXECUCAO_COMANDOS_URL` | vazio | URL da fila de comandos |
| `SQS_CONSUMER_ENABLED` | `false` | Ativa o consumidor após configurar a fila de comandos |
| `SQS_CONSUMER_POLL_DELAY_MS` | `5000` | Intervalo entre leituras da fila de comandos |
| `SQS_CONSUMER_MAX_MESSAGES` | `10` | Mensagens lidas por vez da fila de comandos (1 a 10) |
| `MANAGEMENT_SERVER_PORT` | `8081` | Porta do Actuator (health e métricas Prometheus) |
| `SQS_METRICS_ENABLED` | `false` | Liga as métricas de quantidade de mensagens nas filas e DLQs |
| `SQS_METRICS_POLL_DELAY_MS` | `60000` | Intervalo entre as consultas de métricas ao SQS |
| `SQS_EXECUCAO_COMANDOS_DLQ_URL` | vazio | URL da DLQ de comandos (só para métricas) |
| `SQS_EXECUCAO_RESULTADOS_DLQ_URL` | vazio | URL da DLQ de resultados (só para métricas) |
| `OUTBOX_PUBLISHER_ENABLED` | `false` | Ativa o publicador após configurar a fila |
| `OUTBOX_POLL_DELAY_MS` | `5000` | Intervalo entre buscas na outbox |

Health check: `GET :8081/actuator/health`. Métricas Prometheus: `GET :8081/actuator/prometheus` (detalhes em [observabilidade](docs/observabilidade.md)).

## Contratos

- [Fluxo de negócio: quem chama o quê e o que muda cada status](docs/fluxo-de-negocio.md)
- [Como o serviço funciona: endpoints, consumo e publicação de mensagens](docs/funcionamento-do-servico.md)
- [Observabilidade: métricas de negócio, pod, CPU e limites](docs/observabilidade.md)
- [Collection do Postman com o fluxo completo](docs/execucao-service.postman_collection.json)- [Arquitetura do Execução Service e configuração das DLQs](docs/arquitetura-execucao.md)
- [Guia de integração para OS Service e Billing: filas, mensagens, campos e HTTP](docs/contrato-provisorio-saga.md)

## Observacao importante

Este repositório começou como esqueleto. O primeiro incremento de diagnóstico, catálogo
e cotação já usa DynamoDB e as mesmas rotas HTTP em todos os ambientes. O contrato
`AgendarExecucao` está tipado e documentado. O agendamento já reserva peças e grava
execução, deduplicação e outbox em uma única transação DynamoDB. O publicador da outbox
envia resultados para `execucao-resultados` quando as duas variáveis de SQS acima são
configuradas. O consumidor SQS recebe comandos quando habilitado. A consulta, o início e a conclusão das execuções
estão disponíveis nesta branch. A autenticação e o deploy integrado ainda estão pendentes.

Próximas etapas do serviço:

- Alinhar com o grupo os contratos e a orquestração do cancelamento e do estorno.
- Autenticação e autorização para operadores e serviços nas rotas `/api/v1`.
- Infraestrutura DynamoDB/SQS no ambiente do grupo, cobertura mínima de 80%, BDD,
  quality gate e deploy automatizado. O CI atual ainda executa apenas build e testes.
