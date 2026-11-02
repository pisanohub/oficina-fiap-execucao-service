output "dynamodb_table_name" {
  description = "Nome da tabela DynamoDB do Execucao Service"
  value       = aws_dynamodb_table.execucao_service.name
}

output "dynamodb_table_arn" {
  description = "ARN da tabela DynamoDB do Execucao Service"
  value       = aws_dynamodb_table.execucao_service.arn
}

output "execucao_comandos_queue_url" {
  description = "URL da fila de comandos do Execucao Service"
  value       = aws_sqs_queue.execucao_comandos.url
}

output "execucao_resultados_queue_url" {
  description = "URL da fila de resultados do Execucao Service"
  value       = aws_sqs_queue.execucao_resultados.url
}

output "execucao_comandos_dlq_url" {
  description = "URL da DLQ da fila de comandos"
  value       = aws_sqs_queue.execucao_comandos_dlq.url
}

output "execucao_resultados_dlq_url" {
  description = "URL da DLQ da fila de resultados"
  value       = aws_sqs_queue.execucao_resultados_dlq.url
}
