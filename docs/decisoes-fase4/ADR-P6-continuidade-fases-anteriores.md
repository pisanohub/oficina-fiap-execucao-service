# ADR-P6 — Continuidade das decisões das fases anteriores

- **Estado:** Aceita, implementação parcial
- **Data da decisão:** 15/10/2026 (kickoff da Fase 4)
- **Consolidação:** Guilherme, 08/11/2026
- **Escopo:** os três serviços e a infraestrutura

## Contexto

As Fases 1 a 3 deixaram decisões que funcionaram e foram validadas: arquitetura em camadas, linguagem do domínio, observabilidade com New Relic, infraestrutura com Terraform e o API Gateway com a Lambda de autenticação. Na Fase 4, o monólito vira três serviços. Era preciso decidir o que se mantém e o que muda, para não reabrir decisões que já estavam resolvidas com o prazo curto.

## Decisão

Manter, em cada serviço novo:

| Decisão anterior | Como continua na Fase 4 |
| --- | --- |
| Arquitetura em camadas (`domain` / `application` / `adapter`) | Mantida em cada serviço. |
| Linguagem Ubíqua e Domain Storytelling (Fase 1) | Base dos contextos delimitados de cada serviço (ADR-P1). |
| New Relic com OpenTelemetry (ADR-004) | Estendido para **rastreamento distribuído**: o trace precisa atravessar as mensagens do SQS. |
| Terraform com backend remoto no S3 e branch protection com check obrigatório (Fases 2 e 3) | Replicados nos novos repositórios. |
| Lambda de autenticação e API Gateway (RFC-001/002) | Reaproveitados como porta de entrada. |

## Alternativas consideradas

### Rever cada decisão do zero para a arquitetura de microsserviços

Daria liberdade para trocar ferramentas (por exemplo, Prometheus e Grafana em vez do New Relic), mas o enunciado pede para reaproveitar a observabilidade da Fase 3, e o prazo não comporta reaprender ferramentas já dominadas. Descartada.

### Arquitetura diferente por serviço (cada dono escolhe)

Daria autonomia aos donos, mas deixaria o código, os pipelines e o monitoramento diferentes entre os serviços, o que dificulta revisão, apoio mútuo e demonstração. Descartada.

## Consequências

### Positivas

- Menos decisões novas: o grupo foca no que é novo na fase (Saga, mensageria, bancos separados).
- Código e pipelines parecidos entre os serviços, o que facilita revisão e apoio entre os integrantes.
- Observabilidade e autenticação já validadas na Fase 3.

### Negativas e riscos

- O rastreamento por SQS não vem pronto: cada serviço precisa enviar e ler o contexto do trace (`traceparent`) nos atributos das mensagens.
- A porta de entrada da Fase 3 apontava para um único backend. As rotas para os três serviços e a autenticação entre serviços precisam ser redesenhadas.

## Situação em 08/11/2026

| Item | Situação |
| --- | --- |
| Camadas `domain`/`application`/`adapter` | Seguidas no Execução e no Billing. |
| Observabilidade | Execução: métricas de negócio, filas e limites do container (Prometheus), traces OpenTelemetry com o contexto atravessando o SQS, e logs em JSON correlacionados no mesmo padrão do `oficina_fiap`. **Falta:** instalar o `nri-bundle` no cluster (fica com o Guilherme, conforme o checklist de infra), cadastrar a license key nos repositórios, dashboard e alertas, e o OS e o Billing propagarem o `traceparent`. |
| Terraform e S3 | Filas SQS no `oficina-fiap-infra-k8s`, RDS no `oficina-fiap-infra-database`, tabela DynamoDB criada pelo Pedro. |
| Branch protection | Pendente nos três repositórios de serviço (check `build-test` obrigatório). |
| API Gateway e Lambda | Pendente: rotas para os serviços novos e autenticação entre serviços. |
