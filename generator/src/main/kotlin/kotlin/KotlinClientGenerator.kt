/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.api.kotlin

import com.google.api.kotlin.generator.BuilderGenerator
import com.google.api.kotlin.generator.config.ConfigurationMetadata
import com.google.api.kotlin.generator.config.ConfigurationMetadataFactory
import com.google.api.kotlin.generator.config.ProtobufTypeMapper
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.compiler.PluginProtos
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import mu.KotlinLogging
import java.util.Calendar

private val log = KotlinLogging.logger {}

/**
 * Generator for Kotlin client libraries.
 *
 * @author jbolinger
 */
internal class KotlinClientGenerator(
    private val clientGenerator: ClientGenerator,
    private val clientConfigFactory: ConfigurationMetadataFactory,
    private val builderGenerator: BuilderGenerator? = null
) {

    companion object {
        /** protos to ignore if found during processing */
        private val SKIP_PROTOS_WITH_NAME = listOf(
                "google/bytestream/bytestream.proto",
                "google/longrunning/operations.proto")
    }

    /**
     * Generate the client.
     *
     * @param request protobuf code generation request
     * @return response from this plugin to forward to the protobuf code generator
     */
    fun generate(request: CodeGeneratorRequest): CodeGeneratorResponse {
        // create type map
        val typeMap = ProtobufTypeMapper.fromProtos(request.protoFileList)
        log.debug { "Discovered type: $typeMap" }

        // generate code for the services
        val files = request.protoFileList
                .filter { it.serviceCount > 0 }
                .filter { request.fileToGenerateList.contains(it.name) }
                .filter { !SKIP_PROTOS_WITH_NAME.contains(it.name) }
                .flatMap {
                    it.serviceList.mapNotNull { service ->
                        try {
                            processProtoService(
                                    it, service, clientConfigFactory.find(it), typeMap)
                        } catch (e: Throwable) {
                            log.error(e) { "Failed to generate client for: ${it.name}" }
                            null
                        }
                    }
                }

        // generate builders
        val builderFiles = builderGenerator?.let {
            g -> g.generate(typeMap).map { toFile(it) }
        } ?: listOf()

        // put it all together
        return CodeGeneratorResponse.newBuilder()
                .addAllFile(files)
                .addAllFile(builderFiles)
                .build()
    }

    /** Process the proto file and extract services to process */
    private fun processProtoService(
        proto: DescriptorProtos.FileDescriptorProto,
        service: DescriptorProtos.ServiceDescriptorProto,
        metadata: ConfigurationMetadata,
        typeMap: ProtobufTypeMapper
    ): PluginProtos.CodeGeneratorResponse.File {
        log.debug { "processing proto: ${proto.name} -> service: ${service.name}" }

        // get package name
        val packageName = if (proto.options?.javaPackage.isNullOrBlank()) {
            proto.`package`
        } else {
            proto.options.javaPackage
        }

        // create context
        val className = ClassName(packageName, "${service.name}Client")
        val ctx = GeneratorContext(proto, service, metadata, className, typeMap)

        // generate
        val (type, imports) = clientGenerator.generateServiceClient(ctx)
        val fileSpec = FileSpec.builder(packageName, className.simpleName())

        // add implementation
        fileSpec.addType(type)
        imports.forEach { fileSpec.addStaticImport(it.packageName(), it.simpleName()) }

        // create file response
        return toFile(fileSpec)
    }

    private fun toFile(
        fileSpec: FileSpec.Builder,
        addLicense: Boolean = true
    ): PluginProtos.CodeGeneratorResponse.File {
        // add headers
        if (addLicense) {
            // TODO: not sure how this will be configured long term
            val license = this.javaClass.getResource("/license-templates/apache-2.0.txt")?.readText()
            license?.let {
                fileSpec.addComment("%L", it
                        .replaceFirst("[yyyy]", "${Calendar.getInstance().get(Calendar.YEAR)}")
                        .replaceFirst("[name of copyright owner]", "Google LLC"))
            }
        }

        // put it together and create file
        val file = fileSpec.build()
        val fileDir = file.packageName
                .toLowerCase()
                .split(".")
                .joinToString("/")
        return PluginProtos.CodeGeneratorResponse.File.newBuilder()
                .setName("$fileDir/${file.name}.kt")
                .setContent(file.toString())
                .build()
    }
}

/**
 * Generators for concrete types or transports (gRPC, Retrofit, etc.).
 *
 * A concrete generator will be used based on user settings.
 */
internal interface ClientGenerator {

    /**
     * Generate the client.
     *
     * @param ctx content
     * @return
     */
    fun generateServiceClient(ctx: GeneratorContext): GeneratorResponse
}

/** Data model for client generation */
internal data class GeneratorContext(
    val proto: DescriptorProtos.FileDescriptorProto,
    val service: DescriptorProtos.ServiceDescriptorProto,
    val metadata: ConfigurationMetadata,
    val className: ClassName,
    val typeMap: ProtobufTypeMapper
)

/** Type definition for the client */
internal data class GeneratorResponse(val type: TypeSpec, val imports: List<ClassName> = listOf())
