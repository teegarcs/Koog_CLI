package com.teegarcs.specagent.spec

import io.swagger.v3.parser.OpenAPIV3Parser
import java.io.File
import kotlin.sequences.forEach

/**
 * Utility with the purpose of finding all OpenAPI specs within a specific directory
 * and mapping those specs to a list of capabilities.
 *
 * @param specPath - path with OpenAPI Specs
 * @return list of capabilities
 */
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