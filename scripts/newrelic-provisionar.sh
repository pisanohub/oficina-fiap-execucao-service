#!/usr/bin/env bash
# shellcheck disable=SC2016 # as variáveis $x nas consultas são do GraphQL, não do shell
# Cria ou atualiza no New Relic o dashboard "Execução Service", a política de alertas e as condições NRQL,
# a partir de observability/newrelic/dashboard-execucao.json e observability/newrelic/alertas.json.
#
# Uso:
#   export NEW_RELIC_API_KEY=NRAK-...      # User key (não é a license key de ingestão)
#   export NEW_RELIC_ACCOUNT_ID=1234567
#   scripts/newrelic-provisionar.sh [tudo|dashboard|alertas|validar]
#
# Opcional: NEW_RELIC_API_URL=https://api.eu.newrelic.com/graphql para contas na região EU.
# "validar" só confere os arquivos e monta os payloads, sem chamar a API.

set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DASHBOARD_JSON="$RAIZ/observability/newrelic/dashboard-execucao.json"
ALERTAS_JSON="$RAIZ/observability/newrelic/alertas.json"
API_URL="${NEW_RELIC_API_URL:-https://api.newrelic.com/graphql}"
ACAO="${1:-tudo}"

erro() {
  echo "ERRO: $*" >&2
  exit 1
}

for comando in curl jq; do
  command -v "$comando" >/dev/null 2>&1 || erro "'$comando' não está instalado."
done

case "$ACAO" in
  tudo | dashboard | alertas | validar) ;;
  *) erro "ação inválida '$ACAO'. Use tudo, dashboard, alertas ou validar." ;;
esac

jq -e '.name and (.pages | length > 0)' "$DASHBOARD_JSON" >/dev/null || erro "$DASHBOARD_JSON inválido."
jq -e '.policy.name and (.conditions | length > 0)' "$ALERTAS_JSON" >/dev/null || erro "$ALERTAS_JSON inválido."

if [ "$ACAO" = "validar" ]; then
  NEW_RELIC_ACCOUNT_ID="${NEW_RELIC_ACCOUNT_ID:-0}"
else
  [ -n "${NEW_RELIC_API_KEY:-}" ] || erro "defina NEW_RELIC_API_KEY (User key NRAK-..., em one.newrelic.com > API keys)."
  [ -n "${NEW_RELIC_ACCOUNT_ID:-}" ] || erro "defina NEW_RELIC_ACCOUNT_ID (id numérico da conta)."
fi
[[ "$NEW_RELIC_ACCOUNT_ID" =~ ^[0-9]+$ ]] || erro "NEW_RELIC_ACCOUNT_ID deve ser numérico."

# Executa uma operação NerdGraph. A chave vai no cabeçalho por um descritor de arquivo,
# para não aparecer na linha de comando (ps) nem na saída.
nerdgraph() {
  local consulta="$1" variaveis="$2" corpo resposta
  corpo="$(jq -n --arg q "$consulta" --argjson v "$variaveis" '{query: $q, variables: $v}')"
  resposta="$(curl -sS -X POST "$API_URL" \
    -H 'Content-Type: application/json' \
    -H @<(printf 'API-Key: %s\n' "$NEW_RELIC_API_KEY") \
    --data-binary @- <<<"$corpo")" || erro "falha na chamada ao NerdGraph."
  if ! jq -e . >/dev/null 2>&1 <<<"$resposta"; then
    erro "resposta inesperada do NerdGraph: $(head -c 500 <<<"$resposta")"
  fi
  if jq -e '(.errors // []) | length > 0' >/dev/null <<<"$resposta"; then
    erro "NerdGraph retornou erro: $(jq -c '.errors' <<<"$resposta")"
  fi
  printf '%s' "$resposta"
}

dashboard_payload() {
  jq --argjson conta "$NEW_RELIC_ACCOUNT_ID" \
    '(.. | objects | select(has("accountIds")) | .accountIds) |= [$conta]' "$DASHBOARD_JSON"
}

provisionar_dashboard() {
  local dashboard nome busca guid resposta
  dashboard="$(dashboard_payload)"
  nome="$(jq -r '.name' <<<"$dashboard")"

  busca="$(nerdgraph 'query($q: String!) { actor { entitySearch(query: $q) { results { entities { guid name } } } } }' \
    "$(jq -n --arg q "type = 'DASHBOARD' AND accountId = $NEW_RELIC_ACCOUNT_ID AND name = '$nome'" '{q: $q}')")"
  guid="$(jq -r --arg n "$nome" '[.data.actor.entitySearch.results.entities[]? | select(.name == $n) | .guid][0] // empty' <<<"$busca")"

  if [ -n "$guid" ]; then
    echo "Atualizando o dashboard '$nome' ($guid)..."
    resposta="$(nerdgraph 'mutation($guid: EntityGuid!, $dashboard: DashboardInput!) {
        dashboardUpdate(guid: $guid, dashboard: $dashboard) { entityResult { guid name } errors { description type } } }' \
      "$(jq -n --arg guid "$guid" --argjson d "$dashboard" '{guid: $guid, dashboard: $d}')")"
    jq -e '(.data.dashboardUpdate.errors // []) | length == 0' >/dev/null <<<"$resposta" \
      || erro "dashboardUpdate: $(jq -c '.data.dashboardUpdate.errors' <<<"$resposta")"
  else
    echo "Criando o dashboard '$nome'..."
    resposta="$(nerdgraph 'mutation($accountId: Int!, $dashboard: DashboardInput!) {
        dashboardCreate(accountId: $accountId, dashboard: $dashboard) { entityResult { guid name } errors { description type } } }' \
      "$(jq -n --argjson a "$NEW_RELIC_ACCOUNT_ID" --argjson d "$dashboard" '{accountId: $a, dashboard: $d}')")"
    jq -e '(.data.dashboardCreate.errors // []) | length == 0' >/dev/null <<<"$resposta" \
      || erro "dashboardCreate: $(jq -c '.data.dashboardCreate.errors' <<<"$resposta")"
    guid="$(jq -r '.data.dashboardCreate.entityResult.guid' <<<"$resposta")"
  fi
  echo "Dashboard pronto: guid $guid"
}

provisionar_alertas() {
  local politica nome_politica busca policy_id existentes total i condicao nome_condicao id_condicao resposta
  politica="$(jq -c '.policy' "$ALERTAS_JSON")"
  nome_politica="$(jq -r '.name' <<<"$politica")"

  busca="$(nerdgraph 'query($accountId: Int!, $name: String!) { actor { account(id: $accountId) { alerts {
        policiesSearch(searchCriteria: { name: $name }) { policies { id name } } } } } }' \
    "$(jq -n --argjson a "$NEW_RELIC_ACCOUNT_ID" --arg n "$nome_politica" '{accountId: $a, name: $n}')")"
  policy_id="$(jq -r --arg n "$nome_politica" '[.data.actor.account.alerts.policiesSearch.policies[]? | select(.name == $n) | .id][0] // empty' <<<"$busca")"

  if [ -z "$policy_id" ]; then
    echo "Criando a política de alertas '$nome_politica'..."
    resposta="$(nerdgraph 'mutation($accountId: Int!, $policy: AlertsPolicyInput!) {
        alertsPolicyCreate(accountId: $accountId, policy: $policy) { id name } }' \
      "$(jq -n --argjson a "$NEW_RELIC_ACCOUNT_ID" --argjson p "$politica" '{accountId: $a, policy: $p}')")"
    policy_id="$(jq -r '.data.alertsPolicyCreate.id' <<<"$resposta")"
  else
    echo "Política '$nome_politica' já existe ($policy_id)."
  fi

  existentes="$(nerdgraph 'query($accountId: Int!, $policyId: ID!) { actor { account(id: $accountId) { alerts {
        nrqlConditionsSearch(searchCriteria: { policyId: $policyId }) { nrqlConditions { id name } } } } } }' \
    "$(jq -n --argjson a "$NEW_RELIC_ACCOUNT_ID" --arg p "$policy_id" '{accountId: $a, policyId: $p}')")"

  total="$(jq '.conditions | length' "$ALERTAS_JSON")"
  for ((i = 0; i < total; i++)); do
    condicao="$(jq -c ".conditions[$i]" "$ALERTAS_JSON")"
    nome_condicao="$(jq -r '.name' <<<"$condicao")"
    id_condicao="$(jq -r --arg n "$nome_condicao" \
      '[.data.actor.account.alerts.nrqlConditionsSearch.nrqlConditions[]? | select(.name == $n) | .id][0] // empty' <<<"$existentes")"

    if [ -n "$id_condicao" ]; then
      echo "  Atualizando condição '$nome_condicao' ($id_condicao)..."
      nerdgraph 'mutation($accountId: Int!, $id: ID!, $condition: AlertsNrqlConditionUpdateStaticInput!) {
          alertsNrqlConditionStaticUpdate(accountId: $accountId, id: $id, condition: $condition) { id name } }' \
        "$(jq -n --argjson a "$NEW_RELIC_ACCOUNT_ID" --arg id "$id_condicao" --argjson c "$condicao" '{accountId: $a, id: $id, condition: $c}')" >/dev/null
    else
      echo "  Criando condição '$nome_condicao'..."
      nerdgraph 'mutation($accountId: Int!, $policyId: ID!, $condition: AlertsNrqlConditionStaticInput!) {
          alertsNrqlConditionStaticCreate(accountId: $accountId, policyId: $policyId, condition: $condition) { id name } }' \
        "$(jq -n --argjson a "$NEW_RELIC_ACCOUNT_ID" --arg p "$policy_id" --argjson c "$condicao" '{accountId: $a, policyId: $p, condition: $c}')" >/dev/null
    fi
  done
  echo "Alertas prontos: política $policy_id com $total condições."
  echo "Lembrete: conecte a política a um destino de notificação (Alerts > Workflows) para receber os avisos."
}

case "$ACAO" in
  validar)
    dashboard_payload | jq -e '[.. | objects | select(has("accountIds")) | .accountIds[0]] | all(. == '"$NEW_RELIC_ACCOUNT_ID"')' >/dev/null
    echo "Dashboard: $(jq '.pages | length' "$DASHBOARD_JSON") páginas, $(jq '[.pages[].widgets[]] | length' "$DASHBOARD_JSON") widgets."
    echo "Alertas: política '$(jq -r '.policy.name' "$ALERTAS_JSON")', $(jq '.conditions | length' "$ALERTAS_JSON") condições."
    ;;
  dashboard) provisionar_dashboard ;;
  alertas) provisionar_alertas ;;
  tudo)
    provisionar_dashboard
    provisionar_alertas
    ;;
esac
