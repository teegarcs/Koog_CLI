package com.teegarcs.specagent.agent

import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.security.SecurityScheme

data class SpecCapability(
    val operationId: String,
    val server: String,
    val path: String,
    val description: String,
    val httpMethod: PathItem.HttpMethod,
    val details: Operation,
    val securitySchemes: Map<String, SecurityScheme>
)