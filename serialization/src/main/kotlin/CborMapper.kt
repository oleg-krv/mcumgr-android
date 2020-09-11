package com.juul.mcumgr.serialization

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.juul.mcumgr.CoreReadRequest
import com.juul.mcumgr.CoreReadResponse
import com.juul.mcumgr.EchoRequest
import com.juul.mcumgr.EchoResponse
import com.juul.mcumgr.FileReadRequest
import com.juul.mcumgr.FileReadResponse
import com.juul.mcumgr.FileWriteRequest
import com.juul.mcumgr.FileWriteResponse
import com.juul.mcumgr.ImageWriteRequest
import com.juul.mcumgr.ImageWriteResponse
import com.juul.mcumgr.TaskStatsRequest
import com.juul.mcumgr.TaskStatsResponse
import com.juul.mcumgr.message.Request
import kotlin.reflect.KClass

/**
 * CBOR object mapper used my mcumgr serialization.
 */
val cbor = CBORMapper().apply {
    registerModule(KotlinModule())

    // Do not fail on unknown properties when decoding responses
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    // Do not fail when attempting to serialize an object w/ no annotated fields
    disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

    // Do not serialize fields with null values
    setSerializationInclusion(JsonInclude.Include.NON_NULL)

    // Fail if a required property is not available
    enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)

    // System
    addMixIn(EchoRequest::class.java, System.EchoRequest::class.java)
    addMixIn(EchoResponse::class.java, System.EchoResponse::class.java)
    addMixIn(TaskStatsRequest::class.java, System.TaskStatsRequest::class.java)
    addMixIn(TaskStatsResponse::class.java, System.TaskStatsResponse::class.java)
    addMixIn(TaskStatsResponse.Task::class.java, System.TaskStatsResponse.Task::class.java)

    // Image
    addMixIn(ImageWriteRequest::class.java, Image.ImageWriteRequest::class.java)
    addMixIn(ImageWriteResponse::class.java, Image.ImageWriteResponse::class.java)
    addMixIn(CoreReadRequest::class.java, Image.CoreReadRequest::class.java)
    addMixIn(CoreReadResponse::class.java, Image.CoreReadResponse::class.java)

    // Files
    addMixIn(FileWriteRequest::class.java, File.WriteRequest::class.java)
    addMixIn(FileWriteResponse::class.java, File.WriteResponse::class.java)
    addMixIn(FileReadRequest::class.java, File.ReadRequest::class.java)
    addMixIn(FileReadResponse::class.java, File.ReadResponse::class.java)
}
