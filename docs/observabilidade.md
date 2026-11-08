# Observabilidade do Execução Service

Este documento descreve as métricas que o serviço expõe, como elas chegam ao New Relic e como consultá-las. O desenho segue o do `oficina_fiap` (ADR-004 daquele projeto): Micrometer expõe as métricas em formato Prometheus numa porta de gerenciamento, e o `nri-bundle` do New Relic coleta os pods do cluster.

> Escopo atual: **métricas** (negócio, JVM, CPU, memória e limites do container), **traces distribuídos** (OpenTelemetry, com o contexto atravessando as filas SQS e o HTTP) e **logs em JSON** correlacionados (`trace.id`, `span.id`, `os.id`, `saga.id`, `request.id`). Falta o dashboard e os alertas no New Relic (ver [pendências](#o-que-ainda-não-está-feito)).

## Como funciona

```mermaid
flowchart LR
    APP["Execução Service\n/actuator/prometheus :8081"] -->|scrape| NRP["nri-prometheus\n(nri-bundle)"]
    K8S["Pods do cluster\nkube-state-metrics + newrelic-infrastructure"] --> NR
    NRP --> NR[(New Relic)]
    APP -.->|opcional: consulta GetQueueAttributes| SQS[(SQS: filas e DLQs)]
```

| Origem | O que entrega | Quem fornece |
| --- | --- | --- |
| Micrometer no serviço | Métricas de negócio (`oficina_execucao_*`), HTTP, JVM, CPU do processo e limites lidos do cgroup (`oficina_container_*`) | Este repositório |
| `newrelic-infrastructure` e `kube-state-metrics` | CPU, memória, **requests e limits** dos pods, reinícios, réplicas | `nri-bundle` instalado no cluster |
| `nri-prometheus` | Coleta o `/actuator/prometheus` dos pods com a anotação do `k8s/metrics-service.yaml` | `nri-bundle` instalado no cluster |

### Porta de gerenciamento

O Actuator fica na porta **8081** (`MANAGEMENT_SERVER_PORT`), separada da API (8080), como no `oficina_fiap`. Assim as métricas não passam pelo Service principal nem pelo futuro Gateway.

| Endereço | Uso |
| --- | --- |
| `GET :8081/actuator/health` | Saúde geral |
| `GET :8081/actuator/health/liveness` e `/readiness` | Probes do Kubernetes |
| `GET :8081/actuator/prometheus` | Todas as métricas, formato Prometheus |
| `GET :8081/actuator/metrics` | Lista e consulta de métricas (formato Micrometer) |

No Kubernetes, o `k8s/metrics-service.yaml` cria o Service interno `execucao-service-metrics` com as anotações `prometheus.io/scrape`, `path` e `port`, que o `nri-prometheus` usa para descobrir o alvo. O `deployment.yaml` expõe a porta `management` e usa a 8081 nas probes.

## Catálogo de métricas

Nomes no formato Prometheus (como aparecem no endpoint). Todas recebem a etiqueta `application="execucao-service"`.

### Regras de negócio

| Métrica | Tipo | Etiquetas | O que mede |
| --- | --- | --- | --- |
| `oficina_execucao_agendamentos_total` | contador | `resultado`: `agendada`, `rejeitada` | Agendamentos aceitos e rejeitados por falta de estoque. Reenvios do mesmo comando não contam de novo; uma rejeição reenviada conta de novo |
| `oficina_execucao_pecas_reservadas_total` | contador | | Unidades de peças baixadas do estoque pelos agendamentos (serviços não contam) |
| `oficina_execucao_fila_espera_seconds` | histograma | | Tempo entre o agendamento e o início da execução |
| `oficina_execucao_duracao_seconds` | histograma | | Tempo entre o início e a conclusão da execução |
| `oficina_execucao_estoque_reposto_total` | contador | | Unidades repostas pela rota de reposição |
| `oficina_execucao_estoque_ajustes_total` | contador | | Ajustes absolutos de estoque |
| `oficina_execucao_eventos_publicados_total` | contador | `tipo`: `ExecucaoAgendada`, `ExecucaoRejeitada`, `ExecucaoIniciada`, `ExecucaoConcluida`, `ExecucaoCancelada`, `CancelamentoExecucaoRecusado` | Eventos entregues em `execucao-resultados`. É o melhor indicador de desfechos (inclusive cancelamentos aceitos e recusados) |
| `oficina_execucao_eventos_falhas_total` | contador | | Falhas ao publicar um evento (ele é tentado de novo depois do lease) |
| `oficina_execucao_outbox_atraso_seconds` | medidor | | Idade do evento pendente mais antigo da outbox. Perto de 0 é saudável; crescer indica que o publicador parou |

### Consumo de comandos (SQS)

| Métrica | Tipo | Etiquetas | O que mede |
| --- | --- | --- | --- |
| `oficina_execucao_comandos_processados_total` | contador | `tipo`: `AgendarExecucao`, `CancelarExecucao` | Comandos concluídos e confirmados (inclui agendamento rejeitado por estoque, que é um resultado de negócio) |
| `oficina_execucao_comandos_duracao_seconds` | histograma | `tipo` | Tempo de processamento de um comando |
| `oficina_execucao_comandos_falhas_total` | contador | `tipo` (ou `desconhecido`), `natureza`: `permanente`, `transitoria` | Falhas. `permanente` vai à DLQ em segundos; `transitoria` espera o tempo de visibilidade |
| `oficina_execucao_fila_mensagens` | medidor | `fila`: `comandos`, `comandos-dlq`, `resultados`, `resultados-dlq`; `estado`: `visiveis`, `em_processamento` | Mensagens nas filas. **Qualquer valor acima de zero em `*-dlq` pede investigação** |

`oficina_execucao_fila_mensagens` só existe com `SQS_METRICS_ENABLED=true` e precisa das URLs das filas (incluindo as DLQs: `SQS_EXECUCAO_COMANDOS_DLQ_URL` e `SQS_EXECUCAO_RESULTADOS_DLQ_URL`). Ele consulta o SQS a cada `SQS_METRICS_POLL_DELAY_MS` (60 s por padrão), então o valor tem esse atraso.

### Pod, CPU, memória e limites

| Métrica | Tipo | O que mede |
| --- | --- | --- |
| `oficina_container_memoria_limite_bytes` | medidor | Limite de memória do container (lido do cgroup). Só existe se houver limite |
| `oficina_container_memoria_uso_bytes` | medidor | Memória em uso pelo container |
| `oficina_container_cpu_limite_nucleos` | medidor | Limite de CPU em núcleos (por exemplo, `0.5` para `500m`). Só existe se houver limite |
| `process_cpu_usage`, `system_cpu_usage`, `system_cpu_count` | medidor | CPU do processo e do sistema (Micrometer) |
| `jvm_memory_used_bytes`, `jvm_memory_max_bytes`, `jvm_gc_*`, `jvm_threads_*` | medidor/contador | Memória, coleta de lixo e threads da JVM |
| `process_uptime_seconds` | medidor | Tempo desde o início do processo |
| `http_server_requests_seconds` | histograma | Requisições HTTP por rota, método e status (inclui erros `4xx`/`5xx`) |

Os medidores `oficina_container_*` leem `/sys/fs/cgroup` (cgroup v2, com fallback para o v1). Fora de um container, como no Mac em desenvolvimento, eles simplesmente não aparecem.

**Requests e limits do pod** (`256Mi`/`200m` e `512Mi`/`500m`, definidos em `k8s/deployment.yaml`) também chegam ao New Relic pela integração com o Kubernetes, e é essa a fonte para ver o uso **contra** o limite no tempo.

## Como ver as métricas

### Localmente

```bash
curl -s http://localhost:8081/actuator/prometheus | grep oficina_
```

Para ver as métricas de fila, suba a aplicação com `SQS_METRICS_ENABLED=true` e as quatro URLs de fila. Faça o fluxo (agendar, iniciar, concluir, cancelar) e consulte de novo: os contadores e histogramas aparecem depois da primeira ocorrência.

### No New Relic (sugestões)

As consultas abaixo são ponto de partida e **precisam ser conferidas na conta do grupo** (nomes e tipos dependem de como o `nri-prometheus` converte cada métrica).

Desfechos da execução por tipo, ao longo do tempo:

```sql
SELECT sum(oficina_execucao_eventos_publicados_total) FROM Metric
WHERE application = 'execucao-service' FACET tipo TIMESERIES
```

Rejeições por estoque contra agendamentos aceitos:

```sql
SELECT sum(oficina_execucao_agendamentos_total) FROM Metric
WHERE application = 'execucao-service' FACET resultado TIMESERIES
```

Mensagens em DLQ (para alerta quando maior que zero):

```sql
SELECT latest(oficina_execucao_fila_mensagens) FROM Metric
WHERE application = 'execucao-service' AND fila LIKE '%dlq' AND estado = 'visiveis' FACET fila
```

Atraso da outbox (alerta se passar de 60 s por alguns minutos):

```sql
SELECT max(oficina_execucao_outbox_atraso_seconds) FROM Metric
WHERE application = 'execucao-service' TIMESERIES
```

CPU, memória e limites dos pods (integração Kubernetes):

```sql
SELECT average(cpuUsedCores), max(cpuLimitCores), average(memoryWorkingSetBytes), max(memoryLimitBytes)
FROM K8sContainerSample WHERE containerName = 'execucao-service' FACET podName TIMESERIES
```

### Alertas sugeridos

| Condição | Motivo |
| --- | --- |
| `fila_mensagens{fila=~".*dlq", estado="visiveis"} > 0` | Há comandos ou resultados que não foram processados |
| `outbox_atraso_seconds > 60` por 5 min | O publicador está parado ou o SQS está indisponível |
| `comandos_falhas_total{natureza="permanente"}` em alta | Mensagens inválidas chegando do OS Service |
| Memória do container acima de 80% do limite | Risco de reinício por falta de memória |
| `agendamentos_total{resultado="rejeitada"}` em alta | Falta de estoque |

## Configuração

| Variável | Padrão | Descrição |
| --- | --- | --- |
| `MANAGEMENT_SERVER_PORT` | `8081` | Porta do Actuator e do Prometheus |
| `SQS_METRICS_ENABLED` | `false` | Liga a consulta de filas para `oficina_execucao_fila_mensagens` |
| `SQS_METRICS_POLL_DELAY_MS` | `60000` | Intervalo entre consultas ao SQS |
| `SQS_EXECUCAO_COMANDOS_DLQ_URL` | vazio | URL da DLQ de comandos (para as métricas) |
| `SQS_EXECUCAO_RESULTADOS_DLQ_URL` | vazio | URL da DLQ de resultados (para as métricas) |
| `SPRING_PROFILES_ACTIVE` | vazio | `newrelic` liga a exportação de traces para o New Relic |
| `NEW_RELIC_LICENSE_KEY` | vazio | License key de ingestão (do secret `newrelic-license` no cluster) |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `https://otlp.nr-data.net/v1/traces` | Endpoint OTLP dos traces no profile `newrelic` |
| `OTEL_SERVICE_NAME`, `DEPLOYMENT_ENVIRONMENT` | `execucao-service`, `production` | Atributos do recurso nos traces |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Fração de traces amostrados |

A consulta às filas precisa da permissão `sqs:GetQueueAttributes` nas quatro filas (a LabRole deve cobrir, mas convém confirmar com o Pedro).

## Traces distribuídos

O serviço usa o `spring-boot-starter-opentelemetry` (Micrometer Tracing com OpenTelemetry), como o `oficina_fiap`. Cada requisição HTTP vira um span automaticamente. O consumo e a publicação de mensagens usam a **Observation API do Micrometer**, a forma recomendada pelo Spring, que gera o span e o tempo (métrica) da mesma operação. O **contexto do trace atravessa as filas** no padrão W3C (`traceparent`):

```mermaid
sequenceDiagram
    participant OS as OS Service
    participant C as execucao-comandos
    participant E as Execução Service
    participant DB as DynamoDB (outbox)
    participant R as execucao-resultados
    OS->>C: AgendarExecucao + atributo traceparent
    C->>E: span "process execucao-comandos" (continua o trace do OS)
    E->>DB: grava o evento com o contexto do trace
    Note over E,DB: depois, o publicador da outbox
    E->>R: span "send execucao-resultados" + atributo traceparent
    R->>OS: o OS continua o mesmo trace
```

| Span | Tipo | Quando | Atributos |
| --- | --- | --- | --- |
| `http <método> <rota>` | server | Toda chamada HTTP (automático) | rota, status |
| `process execucao-comandos` | consumer | Para cada comando lido da fila | `messaging.*`, `messaging.message.id`, `oficina.os.id`, `oficina.saga.id` |
| `send execucao-resultados` | producer | Para cada evento publicado pela outbox | `messaging.*`, `oficina.evento.tipo`, `oficina.os.id`, `oficina.saga.id` |

Os nomes seguem a convenção atual do OpenTelemetry para mensageria (`<operação> <fila>`, com `messaging.system=aws_sqs`). Os atributos próprios do domínio têm o prefixo `oficina.`.

As mesmas observações geram as métricas `execucao_mensagens_consumo_seconds` e `execucao_mensagens_publicacao_seconds` (tempo e quantidade, com a tag `error` quando falha).

### Onde fica cada parte

Tudo fica em `config/observability`, no mesmo padrão do `oficina_fiap`. As classes de negócio e os adaptadores não lidam com spans:

| Classe | Papel |
| --- | --- |
| `RequestCorrelationFilter` | `X-Request-Id` e `os.id` da rota no MDC e log de cada requisição (igual ao do `oficina_fiap`) |
| `ObservacaoMensagens` | Observações de consumo e publicação SQS, e `os.id`/`saga.id` no span e no MDC |
| `ContextoRastreamento` | Captura o contexto do trace para a outbox e o restaura na hora de publicar |
| `CamposCorrelacao` | Nomes dos campos de correlação nos logs |

O consumidor (`AgendarExecucaoConsumer`) e o publicador (`OutboxPublisher`) só chamam `observacao.consumo(...)` e `observacao.publicacao(...)`.

- **Entrada:** se o comando chega com o atributo de mensagem SQS `traceparent`, o processamento continua aquele trace. Sem ele, começa um trace novo.
- **Outbox:** o contexto ativo no momento em que o evento é gravado (o comando ou a chamada HTTP do mecânico) fica salvo no item da outbox (`rastreamento`). Quando o publicador envia o evento, segundos depois, ele continua **o mesmo trace** e manda o `traceparent` como atributo da mensagem.
- **Ruído evitado:** as tarefas agendadas de polling (consumidor e publicador) não geram traces (`management.observations.enable.[tasks.scheduled]=false`).
- **Exportação:** com o profile `newrelic`, os traces vão por OTLP para `https://otlp.nr-data.net/v1/traces` com a license key (`application-newrelic.yaml`, igual ao do `oficina_fiap`). Sem o profile e sem endpoint configurado, nada é exportado.
- **Amostragem:** `TRACING_SAMPLING_PROBABILITY` (padrão 1.0, ou seja, todos os traces).

Para o trace atravessar de verdade os serviços, o **OS Service precisa enviar o atributo `traceparent` nos comandos e lê-lo nos eventos** (ver o [guia de integração](contrato-provisorio-saga.md)).

## Logs

Os logs saem em **JSON**, um por linha, prontos para o New Relic Logs. A configuração é a mesma do `oficina_fiap`: `logback-spring.xml` com o `logstash-logback-encoder`. Campos:

| Campo | Origem |
| --- | --- |
| `@timestamp`, `level`, `thread`, `logger`, `message`, `stack_trace` | Padrão |
| `service.name` | `execucao-service` |
| `trace.id`, `span.id` | Trace ativo (é o que liga o log ao trace no New Relic). Omitidos quando não há trace |
| `os.id`, `saga.id` | Do comando processado, do evento publicado ou da rota HTTP |
| `request.id` | Cabeçalho `X-Request-Id` (gerado se não vier), devolvido na resposta |

Cada requisição da API gera uma linha `Requisição HTTP concluída method=... path=... status=... durationMs=...`.

Exemplo real:

```json
{"@timestamp":"2026-11-08T12:41:26.500-03:00","level":"INFO","thread":"http-nio-8080-exec-1",
 "logger":"...RequestCorrelationFilter","message":"Requisição HTTP concluída method=POST path=/api/v1/execucoes/trace-1794152424/inicio status=200 durationMs=33",
 "os.id":"trace-1794152424","request.id":"b4de8451-e318-4ae8-8ce1-01e29f89a51f","service.name":"execucao-service",
 "trace.id":"fc6dd62d7cf940b0aa8cab303218b03c","span.id":"dfb3faa68b030bba"}
```

## O que ainda não está feito

- **License key do New Relic no GitHub**: o deploy cria o secret `newrelic-license` e liga o profile `newrelic` só quando o secret `NEW_RELIC_LICENSE_KEY` existe no repositório. Sem ele, o serviço sobe normalmente, mas não exporta traces.
- **Dashboard e alertas** no New Relic, com as consultas acima como base.
- **Confirmar com o Pedro** que o `nri-bundle` está instalado no cluster e que o `nri-prometheus` enxerga o namespace `oficina-ms` (hoje o `scrape_endpoints: true` do `oficina_fiap` foi validado só para o namespace `of-fiap`).
- **Variáveis de SQS no ConfigMap** do deploy (`SQS_*`, `SQS_METRICS_ENABLED`), que dependem das URLs das filas.
