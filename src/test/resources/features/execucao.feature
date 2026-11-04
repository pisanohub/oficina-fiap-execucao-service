# language: pt
Funcionalidade: Execução de uma ordem de serviço
  Depois que o orçamento é aprovado e o pagamento confirmado, o OS Service pede a execução pela fila
  de comandos. O Execução Service reserva as peças, controla o andamento do trabalho feito pelo mecânico
  e avisa o resultado de cada passo pela fila de resultados.

  Cenário: Fluxo completo, do agendamento até a conclusão
    Dado que a peça "filtro" tem 10 unidades em estoque
    Quando o OS Service envia AgendarExecucao com 2 unidades de "filtro"
    Então a execução fica "AGENDADA"
    E o estoque de "filtro" passa a ser 8
    E o evento "ExecucaoAgendada" é publicado
    Quando o mecânico inicia a execução
    Então a execução fica "EM_EXECUCAO"
    E o evento "ExecucaoIniciada" é publicado
    Quando o mecânico conclui a execução
    Então a execução fica "CONCLUIDA"
    E o evento "ExecucaoConcluida" é publicado

  Cenário: Agendamento rejeitado por falta de estoque
    Dado que a peça "pastilha" tem 2 unidades em estoque
    Quando o OS Service envia AgendarExecucao com 5 unidades de "pastilha"
    Então o evento "ExecucaoRejeitada" é publicado com o motivo "ESTOQUE_INSUFICIENTE"
    E nenhuma execução é criada
    E o estoque de "pastilha" continua 2

  Cenário: Cancelamento antes do início devolve as peças
    Dado que a peça "correia" tem 10 unidades em estoque
    E o OS Service enviou AgendarExecucao com 3 unidades de "correia"
    E a execução fica "AGENDADA"
    E o estoque de "correia" passa a ser 7
    Quando o OS Service envia CancelarExecucao
    Então a execução fica "CANCELADA"
    E o estoque de "correia" passa a ser 10
    E o evento "ExecucaoCancelada" é publicado

  Cenário: Cancelamento recusado depois que o trabalho começou
    Dado que a peça "vela" tem 10 unidades em estoque
    E o OS Service enviou AgendarExecucao com 1 unidades de "vela"
    E a execução fica "AGENDADA"
    E o mecânico inicia a execução
    Quando o OS Service envia CancelarExecucao
    Então o evento "CancelamentoExecucaoRecusado" é publicado com o motivo "ESTADO_NAO_PERMITE_CANCELAMENTO"
    E a execução continua "EM_EXECUCAO"
    E o estoque de "vela" continua 9
