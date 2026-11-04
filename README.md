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
- JaCoCo (cobertura de testes, com trava de 80%) e SonarCloud (análise de qualidade no CI)

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

## Qualidade: cobertura e Sonar

### Cobertura de testes

O JaCoCo mede a cobertura e o build (`./mvnw clean verify`) **falha se a cobertura de linhas ficar abaixo de 80%** (`jacoco.minimum.coverage` no `pom.xml`). O relatório HTML fica em `target/site/jacoco/index.html`.

| Medida (03/11/2026) | Valor |
| --- | --- |
| Testes automatizados (unitários + 4 cenários BDD) | 130 |
| Cobertura de linhas (JaCoCo, usada no gate) | 93,4% |
| Cobertura de instruções (JaCoCo) | 93,3% |
| Cobertura segundo o Sonar (linhas + branches) | [painel no SonarCloud](https://sonarcloud.io/project/overview?id=pisanohub_oficina-fiap-execucao-service) |

### Testes BDD (Cucumber)

O fluxo completo do serviço está descrito em português, no formato Gherkin, em [`src/test/resources/features/execucao.feature`](src/test/resources/features/execucao.feature) e é executado como teste automatizado. Os cenários cobrem:

1. **Fluxo completo:** o OS Service agenda a execução, o mecânico inicia e conclui, com os eventos `ExecucaoAgendada`, `ExecucaoIniciada` e `ExecucaoConcluida` publicados.
2. **Agendamento rejeitado** por falta de estoque (`ExecucaoRejeitada`, sem execução criada e sem mexer no estoque).
3. **Cancelamento antes do início**, que devolve as peças ao estoque (`ExecucaoCancelada`).
4. **Cancelamento recusado** depois que o trabalho começou (`CancelamentoExecucaoRecusado`).

Os passos (`src/test/java/.../bdd`) não usam dublês: o teste sobe o **DynamoDB Local e o ElasticMQ em containers** (Testcontainers), inicia a aplicação inteira com o consumidor e o publicador ligados e conversa com ela como o OS Service e o mecânico fariam, por mensagens no SQS e chamadas HTTP.

```bash
./mvnw test -Dtest=CucumberTest        # só o BDD
./mvnw clean verify                    # tudo (inclui o BDD)
```

Requer **Docker** em execução. Sem Docker, os cenários são ignorados na máquina local, mas **falham no CI** (variável `CI`), para não passarem em branco. O relatório fica em `target/cucumber-reports/cucumber.html`.

### Sonar

O projeto está configurado para o SonarQube/SonarCloud (`sonar-maven-plugin` e propriedades `sonar.*` no `pom.xml`, usando o relatório do JaCoCo). O CI do PR #22 rodou a análise no SonarCloud e o *quality gate* passou. Na análise local de 03/11/2026 com o SonarQube Community, com 0 bugs, 0 vulnerabilidades, 0 *code smells*, 0 *security hotspots*, 0% de duplicação e nota A em confiabilidade, segurança e manutenibilidade.

Análise local, com um SonarQube em `localhost:9000` (token gerado no próprio SonarQube):

```bash
docker run -d --name sonarqube -p 9000:9000 sonarqube:community
./mvnw clean verify
./mvnw sonar:sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=SEU_TOKEN -Dsonar.qualitygate.wait=true
```

No CI (`.github/workflows/ci.yml`), a mesma etapa roda os testes, a trava de cobertura do JaCoCo e a análise do Sonar (`verify` + `sonar:sonar`, no mesmo padrão do OS Service e do Billing). O `-Dsonar.qualitygate.wait=true` faz o job **falhar se o quality gate do Sonar reprovar**, e a regra do JaCoCo faz falhar se a cobertura de linhas ficar abaixo de 80%.

A configuração fica no próprio repositório: a organização (`sonar.organization`) e o servidor (SonarCloud) no `pom.xml`, e a chave do projeto no comando do `ci.yml`. O **único** item externo é o secret `SONAR_TOKEN` do repositório (Settings > Secrets and variables > Actions). Sem ele, a etapa do Sonar falha.

Para o Sonar e a cobertura **barrarem o merge**, o check `build-test` precisa ser marcado como obrigatório na proteção da branch `main`.

## Estado atual

O serviço está funcional e coberto por testes. Resumo do que existe na `main`:

- **API HTTP** (`/api/v1`): catálogo e estoque (listar, consultar, atualizar, inativar, repor e ajustar), diagnóstico com controle de versão, cotação de preços e fila de trabalho (listar com paginação, consultar, iniciar e concluir).
- **Mensageria**: consumo de `AgendarExecucao` e `CancelarExecucao` e publicação dos resultados via outbox, ambos ligados por configuração (`SQS_CONSUMER_ENABLED` e `OUTBOX_PUBLISHER_ENABLED`).
- **Persistência**: DynamoDB, com agendamento, deduplicação e outbox numa única transação.
- **Observabilidade**: métricas de negócio, filas e limites do container expostas em Prometheus (porta 8081).
- **Qualidade**: 126 testes, cobertura de 80% e análise do Sonar barrando o CI.

Próximas etapas do serviço:

- Alinhar com o grupo os contratos e a orquestração do cancelamento e do estorno.
- Autenticação e autorização para operadores e serviços nas rotas `/api/v1`.
- Variáveis de SQS no deploy, BDD (fluxo ponta a ponta), traces distribuídos e dashboard no New Relic.
