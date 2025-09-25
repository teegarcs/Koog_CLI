package com.teegarcs.specagent

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.teegarcs.specagent.agent.AgentArgs
import com.teegarcs.specagent.agent.SpecAgent

/**
 * Main entry point into the Agentic CLI helper.
 */
class Main : SuspendingCliktCommand() {
    override fun help(context: Context): String {
        return "Parses Open API specs and allows user to ask the agent a prompt."
    }

    val path by option(
        names = arrayOf("--path"),
        help = "A directory where the Open API Specs are stored."
    ).file(canBeFile = false).required()

    val prompt by option(
        names = arrayOf("--prompt"),
        help = "A prompt for the agent"
    ).required()

    override suspend fun run() {
        //start up MCP server
        val processBuilder = ProcessBuilder(
            "npx",
            "@brightdata/mcp"
        )

        // set the bright data api key to process env.
        processBuilder.environment()["API_TOKEN"] = BRIGHT_KEY
        val mcpProcess = processBuilder.start()
        val output =
            SpecAgent(mcpProcess).buildAgent().run(
                agentInput = AgentArgs(path = path, prompt = prompt)
            )

        echo(output)

        // kill the mcp server
        mcpProcess.destroy()
    }
}

suspend fun main(args: Array<String>) = Main().main(args)
