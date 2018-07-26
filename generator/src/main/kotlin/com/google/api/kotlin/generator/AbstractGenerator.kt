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

package com.google.api.kotlin.generator

import com.google.api.kotlin.ClientGenerator
import com.google.api.kotlin.GeneratorContext
import com.google.api.kotlin.config.FlattenedMethod
import com.google.api.kotlin.config.PagedResponse
import com.google.api.kotlin.config.ProtobufTypeMapper
import com.google.api.kotlin.types.GrpcTypes
import com.google.common.base.CaseFormat
import com.google.protobuf.DescriptorProtos
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asTypeName
import org.apache.commons.text.WordUtils
import javax.annotation.Generated

// line wrapping helper
internal fun String?.wrap(wrapLength: Int = 100) = if (this != null) {
    WordUtils.wrap(this, wrapLength)
} else {
    null
}

/**
 * Various reusable generator logic and extensions for [DescriptorProtos].
 *
 * @author jbolinger
 */
internal abstract class AbstractGenerator : ClientGenerator {

    /** Create a "generated by" annotation. */
    protected fun createGeneratedByAnnotation(): AnnotationSpec {
        return AnnotationSpec.builder(Generated::class)
                .addMember("%S", this::class.qualifiedName ?: "")
                .build()
    }

    /**
     * Get info about a proto field at the [path] of given [type].
     *
     * For direct properties of the type the path is should contain 1 element (the
     * name of the field). For nested properties more than 1 element can be given.
     */
    protected fun getProtoFieldInfoForPath(
        context: GeneratorContext,
        path: List<String>,
        type: DescriptorProtos.DescriptorProto
    ): ProtoFieldInfo {
        // find current field
        val (name, idx) = "(.+)\\[([0-9])+]".toRegex().matchEntire(path.first())
                ?.destructured?.let { (n, i) -> Pair(n, i.toInt()) }
                ?: Pair(path.first(), -1)

        val field = type.fieldList.first { it.name == name }

        // only support for 0 index is implemented, so bail out if greater
        if (idx > 0) {
            throw IllegalArgumentException("using a non-zero field index is not supported: ${path.joinToString(".")}")
        }

        // if no nesting, we're done
        if (path.size == 1) {
            return ProtoFieldInfo(context.proto, type, field, idx)
        }

        if (context.typeMap.hasProtoTypeDescriptor(field.typeName)) {
            val t = context.typeMap.getProtoTypeDescriptor(field.typeName)
            return getProtoFieldInfoForPath(context, path.subList(1, path.size), t)
        }
        throw IllegalStateException("Type could not be traversed: ${field.typeName}")
    }

    /** Get the real response type for an LRO operation */
    protected fun getLongRunningResponseType(
        ctx: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto
    ): ClassName {
        // TODO: there is no guarantee that this will always hold,
        //       but there isn't any more info in the proto (yet)
        val name = method.inputType.replace("Request\\z".toRegex(), "Response")
        if (name == method.inputType) throw IllegalStateException("Unable to determine Operation response type")
        return ctx.typeMap.getKotlinType(name)
    }

    protected fun getResponseListElementType(
        ctx: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto,
        paging: PagedResponse
    ): ClassName {
        val outputType = ctx.typeMap.getProtoTypeDescriptor(method.outputType)
        val info = getProtoFieldInfoForPath(ctx, paging.responseList.split("."), outputType)
        return info.field.asClassName(ctx.typeMap)
    }

    /**
     * The result of a flattened method with the given [config] including the [parameters]
     * for the method declaration and the [requestObject] that should be passed to the
     * underlying method.
     */
    internal data class FlattenedMethodResult(
        val parameters: List<ParameterSpec>,
        val requestObject: CodeBlock,
        val config: FlattenedMethod
    )

    /** Get the parameters to flatten the [method] using the given [config] and [context]. */
    protected fun getFlattenedParameters(
        context: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto,
        config: FlattenedMethod
    ): FlattenedMethodResult {
        // get request type
        val requestType = context.typeMap.getProtoTypeDescriptor(method.inputType)
        val parametersAsPaths = config.parameters.map { it.split(".") }

        // create parameter list
        val parameters = parametersAsPaths.map { path ->
            val field = getProtoFieldInfoForPath(context, path, requestType).field
            val rawType = field.asClassName(context.typeMap)
            val typeName = when {
                field.isMap(context.typeMap) -> {
                    val (keyType, valueType) = field.describeMap(context.typeMap)
                    Map::class.asTypeName().parameterizedBy(keyType, valueType)
                }
                field.isRepeated() -> List::class.asTypeName().parameterizedBy(rawType)
                else -> rawType
            }
            ParameterSpec.builder(getParameterName(path.last()), typeName).build()
        }

        // create the set of builders to be used to create the request object
        val builders = mutableMapOf<String, CodeBlock.Builder>()

        // add outermost builder
        val code = CodeBlock.builder()
                .add("%T.newBuilder()\n", context.typeMap.getKotlinType(method.inputType))
                .indent()

        // create setter code based on type of field (map vs. repeated, vs. single object)
        fun getSetterCode(fieldInfo: ProtoFieldInfo): String {
            if (fieldInfo.field.isMap(context.typeMap)) {
                return ".${getSetterMapName(fieldInfo.field.name)}(%L)"
            } else if (fieldInfo.field.isRepeated()) {
                return if (fieldInfo.index >= 0) {
                    ".${getSetterRepeatedAtIndexName(fieldInfo.field.name)}(%L)"
                } else {
                    ".${getSetterRepeatedName(fieldInfo.field.name)}(%L)"
                }
            }
            return ".${getSetterName(fieldInfo.field.name)}(%L)"
        }

        // go through the nest properties from left to right
        val maxWidth = parametersAsPaths.map { it.size }.max() ?: 0
        for (i in 1..maxWidth) {
            // terminal node - set the value
            parametersAsPaths.filter { it.size == i }.forEach { path ->
                val currentPath = path.subList(0, i)
                val field = getProtoFieldInfoForPath(context, path, requestType)

                // add to appropriate builder
                val format = "${getSetterCode(field)}\n"
                val value = getParameterName(path.last())
                if (i == 1) {
                    code.add(format, value)
                } else {
                    val key = currentPath.take(currentPath.size - 1).joinToString(".")
                    builders[key]!!.add(format, value)
                }
            }

            // non terminal - ensure a builder exists
            parametersAsPaths.filter { it.size > i }.forEach { path ->
                val currentPath = path.subList(0, i)
                val field = getProtoFieldInfoForPath(context, currentPath, requestType)

                // create a builder for this param, if first time
                val key = currentPath.joinToString(".")
                if (!builders.containsKey(key)) {
                    val nestedBuilder = CodeBlock.builder()
                            .add("%T.newBuilder()\n", context.typeMap.getKotlinType(field.field.typeName))
                            .indent()
                    builders[key] = nestedBuilder
                }
            }
        }

        // close the outermost and nested builders
        builders.forEach { _, builder -> builder.add(".build()\n").unindent() }

        // build from innermost to outermost
        builders.keys.map { it.split(".") }.sortedBy { it.size }.reversed().forEach { currentPath ->
            val field = getProtoFieldInfoForPath(context, currentPath, requestType)
            val value = builders[currentPath.joinToString(".")]!!.build()
            code.add("${getSetterCode(field)}\n", value)
        }
        code.add(".build()").unindent()

        // put it all together
        return FlattenedMethodResult(parameters, code.build(), config)
    }

    protected fun getSetterMapName(protoFieldName: String) =
            "putAll" + CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, protoFieldName)

    protected fun getSetterRepeatedName(protoFieldName: String) =
            "addAll" + CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, protoFieldName)

    protected fun getSetterRepeatedAtIndexName(protoFieldName: String) =
            "add" + CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, protoFieldName)

    protected fun getSetterName(protoFieldName: String) =
            "set" + CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, protoFieldName)

    protected fun getAccessorName(protoFieldName: String) =
            CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, protoFieldName)

    protected fun getAccessorRepeatedName(protoFieldName: String) =
            CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, protoFieldName) + "List"

    protected fun getParameterName(protoFieldName: String) =
            CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, protoFieldName)
}

// -----------------------------------------------------------------
// Misc. helpers for dealing with proto type descriptors
// -----------------------------------------------------------------

/** Container for the [file], [message], and [field] and repeated [index] (if applicable) of a proto. */
internal data class ProtoFieldInfo(
    val file: DescriptorProtos.FileDescriptorProto,
    val message: DescriptorProtos.DescriptorProto,
    val field: DescriptorProtos.FieldDescriptorProto,
    val index: Int = -1
)

/** Checks if this methods is a LRO. */
internal fun DescriptorProtos.MethodDescriptorProto.isLongRunningOperation() =
        this.outputType == ".google.longrunning.Operation"

/** Checks if this proto field is a map type. */
internal fun DescriptorProtos.FieldDescriptorProto.isMap(typeMap: ProtobufTypeMapper): Boolean {
    if (this.hasTypeName()) {
        if (typeMap.hasProtoEnumDescriptor(this.typeName)) {
            return false
        }
        return typeMap.getProtoTypeDescriptor(this.typeName).options.mapEntry
    }
    return false
}

/** Checks if this proto field is a repeated type. */
internal fun DescriptorProtos.FieldDescriptorProto.isRepeated() =
        this.label == DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED

/** Extracts the key and value Kotlin type names of a protobuf map field using the [typeMap]. */
internal fun DescriptorProtos.FieldDescriptorProto.describeMap(typeMap: ProtobufTypeMapper): Pair<ClassName, ClassName> {
    val mapType = typeMap.getProtoTypeDescriptor(this.typeName)

    // extract key / value type information
    val keyType = mapType.fieldList.find { it.name == "key" }
            ?: throw IllegalStateException("${this.typeName} is not a map type (key type not found)")
    val valueType = mapType.fieldList.find { it.name == "value" }
            ?: throw IllegalStateException("${this.typeName} is not a map type (value type not found)")

    return Pair(keyType.asClassName(typeMap), valueType.asClassName(typeMap))
}

/** Get the comments of a field in message in this proto file, or null if not available. */
internal fun DescriptorProtos.FileDescriptorProto.getParameterComments(fieldInfo: ProtoFieldInfo): String? {
    // find the magic numbers
    val messageNumber = this.messageTypeList.indexOf(fieldInfo.message)
    val fieldNumber = fieldInfo.message.fieldList.indexOf(fieldInfo.field)

    // location is [4, messageNumber, 2, fieldNumber]
    return this.sourceCodeInfo.locationList.filter {
        it.pathCount == 4 &&
                it.pathList[0] == 4 && // message types
                it.pathList[1] == messageNumber &&
                it.pathList[2] == 2 && // fields
                it.pathList[3] == fieldNumber
    }.map { it.leadingComments }.firstOrNull()
}

/** Get the comments of a service method in this protofile, or null if not available */
internal fun DescriptorProtos.FileDescriptorProto.getMethodComments(
    service: DescriptorProtos.ServiceDescriptorProto,
    method: DescriptorProtos.MethodDescriptorProto
): String? {
    // find the magic numbers
    val serviceNumber = this.serviceList.indexOf(service)
    val methodNumber = service.methodList.indexOf(method)

    // location is [6, serviceNumber, 2, methodNumber]
    return this.sourceCodeInfo.locationList.filter {
        it.pathCount == 4 &&
                it.pathList[0] == 6 && // 6 is for service
                it.pathList[1] == serviceNumber &&
                it.pathList[2] == 2 && // 2 is for method (rpc)
                it.pathList[3] == methodNumber &&
                it.hasLeadingComments()
    }.map { it.leadingComments }.firstOrNull()
}

/** Get the kotlin class name of this field using the [typeMap] */
internal fun DescriptorProtos.FieldDescriptorProto.asClassName(typeMap: ProtobufTypeMapper): ClassName {
    return if (this.hasTypeName()) {
        typeMap.getKotlinType(this.typeName)
    } else {
        when (this.type) {
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING -> String::class.asTypeName()
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_BOOL -> BOOLEAN
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_DOUBLE -> DOUBLE
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_FLOAT -> FLOAT
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32 -> INT
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT32 -> INT
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED32 -> INT
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED32 -> INT
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT32 -> INT
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64 -> LONG
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT64 -> LONG
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED64 -> LONG
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED64 -> LONG
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT64 -> LONG
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES -> GrpcTypes.ByteString
            else -> throw IllegalStateException("unexpected or non-primitive type: ${this.type}")
        }
    }
}