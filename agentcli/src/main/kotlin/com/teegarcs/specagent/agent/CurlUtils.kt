package com.teegarcs.specagent.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import kotlinx.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Utility that formats curl commands into a list of strings for use with [ProcessBuilder].
 *
 * @param argsString raw string to format for a [ProcessBuilder] curl command
 * @return List of strings for [ProcessBuilder]
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

/**
 * A tool to be provided to an agent that can then be used to perform various curl requests.
 */
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
            return "Request timed out"
        }

        return output.ifBlank {
            "Output is empty: ${error.ifBlank { "Error is also empty" }}"
        }

    } catch (e: IOException) {
        return e.message ?: "Unknown error occurred"
    }
}