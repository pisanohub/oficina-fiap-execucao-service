output "dynamodb_table_name" {
  description = "Nome da tabela DynamoDB do Execucao Service"
  value       = aws_dynamodb_table.execucao_service.name
}

output "dynamodb_table_arn" {
  description = "ARN da tabela DynamoDB do Execucao Service"
  value       = aws_dynamodb_table.execucao_service.arn
}
