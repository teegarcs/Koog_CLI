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
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.defaultStdioTransport
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import com.teegarcs.specagent.GEMINI_KEY
import com.teegarcs.specagent.QUIVER_KEY
import com.teegarcs.specagent.spec.SpecCapability
import com.teegarcs.specagent.spec.loadSpecFromFile
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Class to build the configuration, strategy, and tools for an agent to fulfill a user request
 * through a provided MCP process, OpenAPI specs, and a prompt from the user.
 */
class SpecAgent(private val mcp: Process) {

    private val executor = simpleGoogleAIExecutor(GEMINI_KEY)

    /**
     * Map of API keys to URLs. Should be updated to provide various
     * API keys to the agent should it need it based on the user queries.
     */
    private val keys = mapOf(
        "https://api.quiverquant.com" to QUIVER_KEY
    )
    private val apiCapabilities: MutableList<SpecCapability> = mutableListOf()

    private val strategy = strategy<AgentArgs, String>("SpecAgent") {
        val nodeRequestLLM by nodeLLMRequestMultiple()
        val nodeExecuteToolMultiple by nodeExecuteMultipleTools(parallelTools = false)
        val nodeSendToolResultMultiple by nodeLLMSendMultipleToolResults()

        /**
         * Load OpenAPI Specs and then provide the details of those specs to the agent via a prompt update.
         *
         * This node takes in AgentArgs with the path and user prompt and outputs the user prompt
         * to provide to the LLMRequestNode.
         */
        val loadSpecNode by node<AgentArgs, String> {
            apiCapabilities.addAll(loadSpecFromFile(it.path))

            // update system prompt to provide new api capabilities
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

            // output user prompt
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
                    onCondition { it.singleOrNull()?.tool == AgentResponseTool.name }
                    transformed { it.single().result!!.toStringDefault() }
        )

        edge(nodeExecuteToolMultiple forwardTo nodeSendToolResultMultiple)

        edge(
            (nodeSendToolResultMultiple forwardTo nodeExecuteToolMultiple)
                    onMultipleToolCalls { true }
        )
    }

    private val tools = ToolRegistry {
        // register tools for use within agent
        tool(AgentResponseTool)
        tool(::retrieveAPIKey)
        tool(::retrieveAPISpec)
        tool(::performCurlRequest)
        tool(::getCurrentDate)

        // set up MCP Server for provided MCP process
        runBlocking {
            val mcpTools = McpToolRegistryProvider.fromTransport(
                transport = McpToolRegistryProvider.defaultStdioTransport(mcp)
            )
            tools(mcpTools.tools)
        }

    }

    /**
     * Builds an [AIAgent] with the provided configuration.
     */
    fun buildAgent(): AIAgent<AgentArgs, String> {
        val config = AIAgentConfig(
            prompt = prompt("SpecAgent-Prompt") {
                system(
                    """
                    You are a helpful assistant.
                    Fulfill the user's query and provide a response in a concise format of around 1-2 sentences.
                    Use available tools to fulfill the user's request. If you cannot fulfill the user's request 
                    with any of tools available, indicate to the user that  you are unable to fulfill their request.
                    After complete, call the AgentResponseTool to return the response to the user. 
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
            /**
             * demo of way to install an event handler and provide some extra logging for an agent.
             */
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

    /**
     * A tool which allows the agent to request an API key for a specific domain should it determine
     * it needs one based on the OpenAPI Spec.
     */
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

    /**
     * A tool which allows the agent to request specific details on how to form an API request for
     * a given operationId.
     */
    @Tool(customName = "APISpec")
    @LLMDescription("Retrieve the API Spec for a given operationId. This provides the information necessary to determine how to form a curl request")
    fun retrieveAPISpec(
        @LLMDescription("operationId key used to retrieve the full api spec.")
        operationId: String
    ): String {
        return apiCapabilities.find { it.operationId.equals(operationId, ignoreCase = true) }
            ?.toString() ?: "No API Spec found for operationId $operationId"

    }

    /**
     * Simple utility for the agent to retrieve the current date should a request be made that requires an accurate date.
     */
    @Tool(customName = "getCurrentDate")
    @LLMDescription("Retrieve the current date in MM/dd/yyyy format")
    fun getCurrentDate(): String {
        val dateFormat = SimpleDateFormat("MM/dd/yyyy")
        return dateFormat.format(Date())
    }
}
