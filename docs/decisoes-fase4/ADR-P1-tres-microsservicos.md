# ADR-P1 — Divisão da oficina em três microsserviços

- **Estado:** Aceita e implementada
- **Data da decisão:** 15/10/2026 (kickoff da Fase 4)
- **Consolidação:** Guilherme, 08/11/2026
- **Repositórios:** [`oficina-fiap-os-service`](https://github.com/pisanohub/oficina-fiap-os-service), [`oficina-fiap-billing-service`](https://github.com/pisanohub/oficina-fiap-billing-service), [`oficina-fiap-execucao-service`](https://github.com/pisanohub/oficina-fiap-execucao-service)

## Contexto

Até a Fase 3, a oficina era uma única aplicação Spring Boot (`oficina_fiap`) com todo o domínio no mesmo processo e no mesmo banco: cliente, veículo, ordem de serviço, itens, orçamento, catálogo de serviços e estoque de peças. A Fase 4 exige no mínimo três microsserviços, cada um com repositório, infraestrutura e banco próprios, sem acesso ao banco de outro serviço.

A divisão precisava seguir fronteiras de negócio, e não camadas técnicas, para que cada serviço evolua e seja implantado sem depender dos outros. A Linguagem Ubíqua e o Domain Storytelling da Fase 1 já separavam três assuntos: o atendimento da ordem, o dinheiro (orçamento e pagamento) e o trabalho na oficina (diagnóstico, peças e reparo).

## Decisão

Dividir a solução em três serviços, seguindo os exemplos do enunciado:

| Serviço | Responsabilidade | Origem no monólito |
| --- | --- | --- |
| **OS Service** | Abertura da OS, status, consulta e histórico. Hospeda o **orquestrador da Saga** (ADR-P4). | `Cliente`, `Veiculo`, `OrdemDeServico`, `StatusOS` |
| **Billing Service** | Geração e envio do orçamento para aprovação, cobrança e confirmação de pagamento (Mercado Pago), estorno. Avisa o OS do resultado. | Cálculo de valores de `ItemOS` e integração nova com o Mercado Pago |
| **Execução e Produção Service** | Diagnóstico, fila de execução, início e conclusão do reparo, **catálogo de serviços** (`Servico`) e **estoque de peças** (`ItemEstoque`). Avisa o OS ao concluir. | Estados de execução que estavam embutidos em `StatusOS`, e `Servico`/`ItemEstoque` migrados |

Decisões tomadas junto, no kickoff:

- **Catálogo e estoque ficam no Execução**, porque é quem consome as peças e consulta o catálogo no dia a dia. O OS e o Billing usam o catálogo por contrato (HTTP ou dados embutidos nos comandos), nunca pelo banco.
- **A porta de entrada continua a da Fase 3**: API Gateway com a Lambda de autenticação (RFC-001/002). A rota nova a definir é API Gateway → OS Service.
- **Não há quarto serviço só para o orquestrador**: a Saga fica dentro do OS (ADR-P4).

## Alternativas consideradas

### Quarto serviço dedicado ao orquestrador da Saga

Separaria a coordenação do domínio da OS, mas acrescentaria mais um repositório, pipeline, deploy e consumo de memória num cluster de nó único (ADR-P5). O orquestrador precisa do estado da OS de qualquer forma. Descartada.

### Catálogo e estoque no OS Service ou num serviço próprio

Deixar o catálogo no OS repetiria o monólito, com o OS dono de quase tudo. Um serviço só de catálogo fragmentaria a reserva de peças, que precisa acontecer na mesma transação do agendamento do trabalho. Descartada: o Execução reserva as peças e cria a execução de forma atômica.

### Divisão por camada técnica (API, regras, persistência)

Não atende ao requisito de banco e deploy independentes por serviço e acopla todas as mudanças de negócio. Descartada.

## Consequências

### Positivas

- Cada serviço tem repositório, pipeline, imagem e Deployment próprios, e pode ser implantado sozinho.
- As fronteiras seguem a linguagem do negócio: atendimento, financeiro e oficina.
- O Execução concentra tudo o que muda o estoque, o que permite reservar peças e agendar o trabalho numa única transação.

### Negativas e riscos

- Consultas que antes eram um `JOIN` viram contratos entre serviços (por exemplo, o preço dos itens). Isso foi resolvido com o **snapshot de preço** no OS (ADR-P4).
- Consistência eventual entre os serviços: o status da OS só muda quando o evento do participante chega.
- Mais repositórios e pipelines para manter (mitigado pelo template de pipeline do Pedro).

## Situação em 08/11/2026

- Os três repositórios existem, com `deploy.yml`, Dockerfile e manifestos Kubernetes. Os três fizeram deploy com sucesso no cluster em 02/11/2026.
- O Execução Service está completo: catálogo, estoque, diagnóstico, cotação, fila de trabalho, consumo de `AgendarExecucao`/`CancelarExecucao` e publicação dos resultados por outbox.
- O Billing tem orçamento, checkout do Mercado Pago e webhook. O OS e a integração por SQS entre os três seguem em desenvolvimento.
