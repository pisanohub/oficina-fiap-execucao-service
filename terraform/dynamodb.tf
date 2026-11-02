# Tabela unica (single-table design) do Execucao e Producao Service (ADR-P2).
# pk = id da Ordem de Servico; sk = tipo do dado daquela OS (ex.: DIAGNOSTICO, EXECUCAO).
# Chave definida com o Guilherme (consumidor da tabela) em 02/11/2026.
resource "aws_dynamodb_table" "execucao_service" {
  name         = "execucao_service"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  tags = {
    Name = "oficina-fiap-execucao-service"
  }
}
