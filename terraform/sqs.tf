# Filas previstas no contrato da Saga. Aplicar somente após confirmar a posse
# desses recursos e seus nomes com a equipe de infraestrutura.
resource "aws_sqs_queue" "execucao_comandos_dlq" {
  name                      = "execucao-comandos-dlq"
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "execucao_comandos" {
  name                       = "execucao-comandos"
  visibility_timeout_seconds = 60
  receive_wait_time_seconds  = 10
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.execucao_comandos_dlq.arn
    maxReceiveCount     = var.sqs_max_receive_count
  })
}

resource "aws_sqs_queue" "execucao_resultados_dlq" {
  name                      = "execucao-resultados-dlq"
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "execucao_resultados" {
  name                       = "execucao-resultados"
  visibility_timeout_seconds = 60
  receive_wait_time_seconds  = 10
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.execucao_resultados_dlq.arn
    maxReceiveCount     = var.sqs_max_receive_count
  })
}
