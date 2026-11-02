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
- Resilience4j (Circuit Breaker nas chamadas sincronas)
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
| `OUTBOX_PUBLISHER_ENABLED` | `false` | Ativa o publicador após configurar a fila |
| `OUTBOX_POLL_DELAY_MS` | `5000` | Intervalo entre buscas na outbox |

Health check: `GET /actuator/health`.

## Contratos

- [Contratos provisórios de diagnóstico e Saga](docs/contrato-provisorio-saga.md)

## Observacao importante

Este repositório começou como esqueleto. O primeiro incremento de diagnóstico, catálogo
e cotação já usa DynamoDB e as mesmas rotas HTTP em todos os ambientes. O contrato
`AgendarExecucao` está tipado e documentado. O agendamento já reserva peças e grava
execução, deduplicação e outbox em uma única transação DynamoDB. O publicador da outbox
envia resultados para `execucao-resultados` quando as duas variáveis de SQS acima são
configuradas. O consumidor SQS recebe comandos quando habilitado. A consulta e o início das execuções
estão disponíveis nesta branch. A conclusão, a autenticação e o deploy integrado
ainda estão pendentes.

Próximas etapas do serviço:

- Conclusão da execução e publicação do evento correspondente.
- Autenticação e autorização para operadores e serviços nas rotas `/api/v1`.
- Infraestrutura DynamoDB/SQS no ambiente do grupo, cobertura mínima de 80%, BDD,
  quality gate e deploy automatizado. O CI atual ainda executa apenas build e testes.
