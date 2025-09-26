package com.teegarcs.specagent.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolArgs
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Tool used to end the agent's work and provide a response back to the user.
 */
object AgentResponseTool : SimpleTool<AgentResponseTool.Args>() {
    /**
     * Represents the arguments for the [AgentResponseTool] tool
     *
     * @property message The input message provided as an argument for the tool.
     */
    @Serializable
    data class Args(val message: String) : ToolArgs

    override suspend fun doExecute(args: Args): String {
        return args.message
    }

    override val argsSerializer: KSerializer<Args>
        get() = Args.serializer()

    override val descriptor: ToolDescriptor
        get() = ToolDescriptor(
            name = "AgentResponseTool",
            description = "Service tool, used by the agent/assistant to end conversation with the response to the user query.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    name = "message",
                    description = "Final message that answers the user's query",
                    type = ToolParameterType.String
                )
            )
        )
}