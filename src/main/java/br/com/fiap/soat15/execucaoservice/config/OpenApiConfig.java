package br.com.fiap.soat15.execucaoservice.config;

import br.com.fiap.soat15.execucaoservice.adapter.in.web.ExecucaoApi;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class OpenApiConfig {
    static final String CABECALHO_REQUEST_ID = "X-Request-Id";
    static final String SCHEMA_PROBLEMA = "Problema";
    static final String MEDIA_TYPE_PROBLEMA = "application/problem+json";

    private static final String DESCRICAO = """
            Microsserviço de **diagnóstico, catálogo/estoque e execução do trabalho** da Oficina FIAP \
            (Tech Challenge, Fase 4).

            Pela API HTTP o serviço:
            - mantém o **catálogo** de peças e serviços, com preço e estoque das peças;
            - registra o **diagnóstico** da OS (itens e quantidades, com controle otimista de versão);
            - responde **cotações** com o preço atual dos itens, que o OS Service guarda como snapshot na OS;
            - expõe a **fila de trabalho**, em que o mecânico inicia e conclui cada execução.

            **Comandos da Saga não usam HTTP.** `AgendarExecucao` e `CancelarExecucao` chegam pela fila SQS \
            `execucao-comandos`, enviados pelo OS Service, e os resultados (`ExecucaoAgendada`, \
            `ExecucaoRejeitada`, `ExecucaoIniciada`, `ExecucaoConcluida`, `ExecucaoCancelada`, \
            `CancelamentoExecucaoRecusado`) são publicados na fila `execucao-resultados` via Transactional \
            Outbox. Por isso não existe rota para criar ou cancelar uma execução.

            Erros seguem o formato `ProblemDetail` (RFC 7807): `400` dados inválidos, `404` recurso não \
            encontrado e `409` conflito de estado, versão ou duplicidade.

            Todas as rotas aceitam o cabeçalho opcional `X-Request-Id` (até 100 caracteres: letras, números, \
            `.`, `_` e `-`), que é devolvido na resposta e usado para correlacionar os logs. Se ausente ou \
            inválido, o serviço gera um UUID. As rotas ainda não exigem autenticação.
            """;

    private static final Map<String, String> TITULOS_ERRO = Map.of(
            "400", "Bad Request",
            "404", "Not Found",
            "409", "Conflict");

    @Bean
    OpenAPI execucaoOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Execução Service API")
                        .version("v1")
                        .description(DESCRICAO))
                .tags(List.of(
                        new Tag().name(ExecucaoApi.TAG_CATALOGO)
                                .description("Cadastro, consulta, atualização e inativação de peças e serviços."),
                        new Tag().name(ExecucaoApi.TAG_ESTOQUE)
                                .description("Reposição e ajuste do estoque de peças. Serviços não têm estoque."),
                        new Tag().name(ExecucaoApi.TAG_DIAGNOSTICO)
                                .description("Diagnóstico da OS: itens e quantidades, com controle otimista de versão."),
                        new Tag().name(ExecucaoApi.TAG_COTACAO)
                                .description("Preços atuais do catálogo, consultados pelo OS Service ao montar a OS."),
                        new Tag().name(ExecucaoApi.TAG_EXECUCOES)
                                .description("Fila de trabalho do mecânico. Execuções são criadas e canceladas "
                                        + "apenas pelos comandos SQS da Saga.")))
                .components(new Components().addSchemas(SCHEMA_PROBLEMA, schemaProblema()));
    }

    @Bean
    OperationCustomizer documentacaoComumDasOperacoes() {
        return (operation, handlerMethod) -> {
            operation.addParametersItem(new HeaderParameter()
                    .name(CABECALHO_REQUEST_ID)
                    .required(false)
                    .description("Identificador de correlação da requisição. Se ausente ou inválido, o serviço gera um UUID.")
                    .schema(new StringSchema().maxLength(100).pattern("^[A-Za-z0-9._-]+$")
                            .example("3f2c9a1e-8b7d-4c1a-9e2f-5d6b7a8c9d0e")));
            if (operation.getResponses() != null) {
                operation.getResponses().forEach((codigo, resposta) -> {
                    resposta.addHeaderObject(CABECALHO_REQUEST_ID, new Header()
                            .description("Mesmo valor recebido em X-Request-Id ou o UUID gerado pelo serviço.")
                            .schema(new StringSchema()));
                    if (codigo.startsWith("4")) {
                        resposta.setContent(conteudoProblema(codigo, resposta.getDescription()));
                    }
                });
            }
            return operation;
        };
    }

    private static Content conteudoProblema(String codigo, String detalhe) {
        Map<String, Object> exemplo = new LinkedHashMap<>();
        exemplo.put("type", "about:blank");
        exemplo.put("title", TITULOS_ERRO.getOrDefault(codigo, "Erro"));
        exemplo.put("status", Integer.valueOf(codigo));
        exemplo.put("detail", detalhe);
        return new Content().addMediaType(MEDIA_TYPE_PROBLEMA, new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/" + SCHEMA_PROBLEMA))
                .example(exemplo));
    }

    private static Schema<?> schemaProblema() {
        return new ObjectSchema()
                .description("Erro no formato ProblemDetail (RFC 7807).")
                .addProperty("type", new StringSchema().description("URI do tipo do problema.").example("about:blank"))
                .addProperty("title", new StringSchema().description("Resumo do status HTTP.").example("Not Found"))
                .addProperty("status", new IntegerSchema().description("Código de status HTTP.").example(404))
                .addProperty("detail", new StringSchema().description("Mensagem explicando o erro.")
                        .example("Execução não encontrada: 123"))
                .addProperty("instance", new StringSchema().description("Caminho da requisição que falhou.")
                        .example("/api/v1/execucoes/123"));
    }
}
