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
| `DYNAMODB_TABLE_NAME` | `execucao_service` | Nome da tabela DynamoDB |

Health check: `GET /actuator/health`.

## Observacao importante

Este repositorio e um **esqueleto inicial**, criado apenas para dar um ponto de partida
(build funcionando, estrutura de pastas, Dockerfile e manifestos Kubernetes basicos,
pipeline de CI simples). Nenhuma logica de dominio foi implementada ainda.

Quem assumir este servico deve seguir o planejamento completo em
`documentacao/divisao-tarefas-fase4.md`, no repositorio principal do grupo, incluindo:

- Modelagem do dominio (catalogo de servicos e estoque de pecas, hoje no monolito).
- Consumo das filas SQS do fluxo de Saga (comando de envio para execucao / evento de
  execucao concluida).
- Integracao DynamoDB real (tabela ainda nao provisionada).
- Circuit Breaker nas chamadas sincronas de consulta de status.
- Testes unitarios, cobertura minima de 80%, fluxo BDD.
- Pipeline de deploy em Kubernetes (este CI ainda so faz build + testes).
