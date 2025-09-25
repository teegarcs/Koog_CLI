package com.teegarcs.specagent.agent

import java.io.File

/**
 * Carry arguments from CLI to the agent to begin processing
 */
data class AgentArgs(
    val path: File,
    val prompt: String,
)
