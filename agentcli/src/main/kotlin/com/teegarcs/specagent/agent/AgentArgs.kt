package com.teegarcs.specagent.agent

import java.io.File

data class AgentArgs(
    val path: File,
    val prompt: String,
)
