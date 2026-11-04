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

## Qualidade: cobertura e Sonar

### Cobertura de testes

O JaCoCo mede a cobertura e o build (`./mvnw clean verify`) **falha se a cobertura de linhas ficar abaixo de 80%** (`jacoco.minimum.coverage` no `pom.xml`). O relatório HTML fica em `target/site/jacoco/index.html`.

| Medida (03/11/2026) | Valor |
| --- | --- |
| Testes automatizados | 126 |
| Cobertura de linhas (JaCoCo, usada no gate) | 89,6% |
| Cobertura de instruções (JaCoCo) | 88,8% |
| Cobertura segundo o Sonar (linhas + branches) | 83,7% |

### Sonar

O projeto está configurado para o SonarQube/SonarCloud (`sonar-maven-plugin` e propriedades `sonar.*` no `pom.xml`, usando o relatório do JaCoCo). Na análise local feita em 03/11/2026 com o SonarQube Community, o *quality gate* passou, com 0 bugs, 0 vulnerabilidades, 0 *code smells*, 0 *security hotspots*, 0% de duplicação e nota A em confiabilidade, segurança e manutenibilidade.

Análise local, com um SonarQube em `localhost:9000` (token gerado no próprio SonarQube):

```bash
docker run -d --name sonarqube -p 9000:9000 sonarqube:community
./mvnw clean verify
./mvnw sonar:sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=SEU_TOKEN -Dsonar.qualitygate.wait=true
```

No CI (`.github/workflows/ci.yml`), depois do build e dos testes, a análise roda quando o secret `SONAR_TOKEN` existe e **o job falha se o quality gate falhar**. Configuração no GitHub (Settings > Secrets and variables > Actions):

| Nome | Tipo | Para quê |
| --- | --- | --- |
| `SONAR_TOKEN` | secret | Token de análise. Sem ele, o CI só emite um aviso |
| `SONAR_HOST_URL` | variável | Endereço do SonarQube. Se vazio, usa `https://sonarcloud.io` |
| `SONAR_PROJECT_KEY` | variável | Chave do projeto, se diferente de `oficina-fiap-execucao-service` |
| `SONAR_ORGANIZATION` | variável | Organização (só no SonarCloud) |
| `SONAR_REQUIRED` | variável | Com `true`, o CI falha quando não há `SONAR_TOKEN`, em vez de apenas avisar |

Para o Sonar e a cobertura **barrarem o merge**, o check `build-test` precisa ser marcado como obrigatório na proteção da branch `main`.

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
- Variáveis de SQS no deploy, BDD (fluxo ponta a ponta), traces distribuídos e dashboard no New Relic.
