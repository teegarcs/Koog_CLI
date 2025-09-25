package com.teegarcs.specagent.spec

import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.security.SecurityScheme

/**
 * a data representation of the capabilities of a specific API found within a
 * processed OpenAPI spec.
 */
data class SpecCapability(
    val operationId: String,
    val server: String,
    val path: String,
    val description: String,
    val httpMethod: PathItem.HttpMethod,
    val details: Operation,
    val securitySchemes: Map<String, SecurityScheme>
)