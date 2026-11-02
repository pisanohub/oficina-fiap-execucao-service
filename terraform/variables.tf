variable "aws_region" {
  description = "Regiao da AWS. O AWS Academy Learner Lab so permite us-east-1 ou us-west-2."
  type        = string
  default     = "us-east-1"
}

variable "sqs_max_receive_count" {
  description = "Numero de recebimentos antes do redirecionamento para a DLQ; validar com o grupo."
  type        = number
  default     = 3

  validation {
    condition     = var.sqs_max_receive_count >= 1 && floor(var.sqs_max_receive_count) == var.sqs_max_receive_count
    error_message = "sqs_max_receive_count deve ser um numero inteiro maior ou igual a 1."
  }
}
