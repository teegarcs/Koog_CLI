package com.teegarcs.specagent.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteMultipleTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequestMultiple
import ai.koog.agents.core.dsl.extension.nodeLLMSendMultipleToolResults
import ai.koog.agents.core.dsl.extension.onMultipleToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.tool
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.defaultStdioTransport
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import io.swagger.v3.parser.OpenAPIV3Parser
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class SPECialAgent(private val mcp: Process) {

    private val executor = simpleGoogleAIExecutor("INSERT_API_KEY_HERE")

    private val keys = mapOf(
        "https://api.quiverquant.com" to "INSERT_API_KEY_HERE"
    )
    private val apiCapabilities: MutableList<SpecCapability> = mutableListOf()

    private val strategy = strategy<AgentArgs, String>("SPECialAgent") {
        val nodeRequestLLM by nodeLLMRequestMultiple()
        val nodeExecuteToolMultiple by nodeExecuteMultipleTools(parallelTools = false)
        val nodeSendToolResultMultiple by nodeLLMSendMultipleToolResults()

        val loadSpecNode by node<AgentArgs, String> {
            apiCapabilities.addAll(loadSpecFromFile(it.path))

            llm.writeSession {
                updatePrompt {
                    system {
                        text(
                            "You have the following API capabilities to fulfill the user's request. " +
                                    "Use the operationId to request API Spec details from the APISpec tool. " +
                                    "This will provide you the necessary information to form a curl request using the CurlRequest tool. " +
                                    "Be sure to pay attention to the securitySchemes from the APISpec and request API keys from tools as needed where the server URL serves as the key to retrieve an API key."
                        )
                        newline()

                        apiCapabilities.forEach { spec ->
                            text("operationId: ${spec.operationId} -> apiDescription: ${spec.description}")
                            newline()
                        }

                    }
                }
            }

            it.prompt
        }

        edge(nodeStart forwardTo loadSpecNode)
        edge(loadSpecNode forwardTo nodeRequestLLM)
        edge(
            nodeRequestLLM forwardTo nodeExecuteToolMultiple
                    onMultipleToolCalls { true }
        )
        edge(
            nodeExecuteToolMultiple forwardTo nodeFinish
                    onCondition { it.singleOrNull()?.tool == ExitTool.name }
                    transformed { it.single().result!!.toStringDefault() }
        )

        edge(nodeExecuteToolMultiple forwardTo nodeSendToolResultMultiple)

        edge(
            (nodeSendToolResultMultiple forwardTo nodeExecuteToolMultiple)
                    onMultipleToolCalls { true }
        )
    }

    private val tools = ToolRegistry {
        tool(ExitTool)
        tool(::retrieveAPIKey)
        tool(::retrieveAPISpec)
        tool(::performCurlRequest)
        tool(::getCurrentDate)
        runBlocking {
            val mcpTools = McpToolRegistryProvider.fromTransport(
                transport = McpToolRegistryProvider.defaultStdioTransport(mcp)
            )
            tools(mcpTools.tools)
        }

    }

    fun buildAgent(): AIAgent<AgentArgs, String> {
        val config = AIAgentConfig(
            prompt = prompt("SPECialAgent-prompt") {
                system(
                    """
                    You are a helpful assistant.
                    Fulfill the user's query and provide a response in a concise format of around 1-2 sentences.
                    Use available tools to fulfill the user's request. If you cannot fulfill their request with any of tools available, indicate to the user that  you are unable to fulfill their request.
                    After complete, call the exit tool to return the response to the user. 
                    """.trimIndent()
                )
            },
            model = GoogleModels.Gemini2_5Pro,
            maxAgentIterations = 50
        )
        return AIAgent(
            promptExecutor = executor,
            strategy = strategy,
            agentConfig = config,
            toolRegistry = tools
        ) {
            handleEvents {
                onBeforeAgentStarted {
                    println("Before Agent Started")
                }
                onBeforeNode {
                    println("Before Node -- Name:${it.node.name}, Input: ${it.input}")
                }
                onAfterNode {
                    println("After Node -- Name:${it.node.name}, Output: ${it.output}")
                }
                onAfterLLMCall {
                    println("After LLMCall -- Prompt:${it.prompt}, Responses: ${it.responses}")
                }
                onToolCall {
                    println("On Tool Call -- Name:${it.tool.name}, Args: ${it.toolArgs}")
                }

                onAgentRunError {
                    println("On Agent Error -- Error:${it.throwable.message}")
                }

                onAgentFinished { ctx ->
                    println("Agent Finished")
                }
            }
        }
    }

    fun loadSpecFromFile(specPath: File): List<SpecCapability> {
        val parser = OpenAPIV3Parser()

        val capabilities = mutableListOf<SpecCapability>()
        val apiSpecs = specPath.listFiles().asSequence()
            .filter { it.isFile && it.name.endsWith("json") }
            .mapNotNull { parser.read(it.absolutePath) }

        apiSpecs.forEach { api ->
            api.paths.forEach { path, pathItem ->
                pathItem.readOperationsMap().forEach {
                    val method = it.key
                    val operation = it.value
                    val description = operation.description.orEmpty()
                    val summary = operation.summary.orEmpty()

                    capabilities.add(
                        SpecCapability(
                            operationId = operation.operationId,
                            server = api.servers.firstOrNull()?.url.orEmpty(),
                            path = path,
                            httpMethod = method,
                            description = "$summary $description",
                            details = operation,
                            securitySchemes = api.components.securitySchemes
                        )
                    )
                }
            }
        }

        return capabilities
    }

    @Tool
    @LLMDescription("retrieve an api key for a given domain")
    fun retrieveAPIKey(
        @LLMDescription("the domain to retrieve an api key for")
        domain: String
    ): String {
        val matchingKey = keys.entries.find { (key, _) ->
            key.contains(domain, ignoreCase = true)
        }
        return matchingKey?.value ?: "No API key found for domain containing $domain"
    }

    @Tool(customName = "APISpec")
    @LLMDescription("Retrieve the API Spec for a given operationId. This provides the information necessary to determine how to form a curl request")
    fun retrieveAPISpec(
        @LLMDescription("operationId key used to retrieve the full api spec.")
        operationId: String
    ): String {
        return apiCapabilities.find { it.operationId.equals(operationId, ignoreCase = true) }
            ?.toString() ?: "No API Spec found for operationId $operationId"

    }

    @Tool(customName = "CurlRequest")
    @LLMDescription(
        "Executes the curl request provided. This tool assumes the provided argument is the body of the curl request. `curl --request {//to be provided by argument}`. " +
                "Example: curl --request GET \\\n" +
                "  --url https://api.domain.com \\\n" +
                "  --header 'Accept: application/json' \\\n" +
                "  --header 'Authorization: Bearer {apikey}'"
    )
    fun performCurlRequest(
        @LLMDescription("body of curl request after `curl --request {//to be provided by argument}`")
        curlRequest: String
    ): String {

        println("Performing curl request")

        val curlCommand = parseCurlArguments(curlRequest)

        try {
            val processBuilder = ProcessBuilder(curlCommand)
            val process = processBuilder.start()

            // Read the output from the command (the API response)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()

            // Wait for the process to complete, with a timeout
            val finished = process.waitFor(10, TimeUnit.SECONDS)

            if (!finished) {
                process.destroy()
                println("Command timed out!")
                return "Request timed out"
            }

            println("\n--- Output (API Response) ---")
            return output.ifBlank {
                "Output is empty: ${error.ifBlank { "Error is also empty" }}"
            }

        } catch (e: IOException) {
            return e.message ?: "Unknown error occurred"
        }
    }

    @Tool(customName = "getCurrentDate")
    @LLMDescription("Retrieve the current date in MM/dd/yyyy format")
    fun getCurrentDate(): String {
        val dateFormat = SimpleDateFormat("MM/dd/yyyy")
        return dateFormat.format(Date())
    }
}

/**
 * Utility that formats curl commands into a list of strings for use with ProcessBuilder.
 */
fun parseCurlArguments(argsString: String): List<String> {
    val cleanedString = argsString.replace("\\\n", " ").replace("\n", " ").trim()

    val regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'")
    val matcher = regex.matcher(cleanedString)
    val parts = mutableListOf<String>()
    while (matcher.find()) {
        val part = matcher.group(1) ?: matcher.group(2) ?: matcher.group()
        parts.add(part)
    }

    val command = mutableListOf("curl")

    if (parts.isNotEmpty()) {
        val firstPart = parts.first().uppercase()
        val httpMethods = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
        if (httpMethods.contains(firstPart)) {
            command.add("--request") // Add the --request flag
            command.addAll(parts)    // Add the rest of the parsed arguments
        } else {
            command.addAll(parts)
        }
    }

    return command
}
