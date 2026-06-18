// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.util

import io.grpc.Metadata
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * Localized string accessor backed by Java ResourceBundle.
 * Loads "strings" bundle from the classpath, honoring the JVM default locale.
 * Falls back to the key itself when a translation is missing.
 */
object Strings {
    private val bundle: ResourceBundle by lazy {
        ResourceBundle.getBundle("strings", Locale.getDefault())
    }

    operator fun get(key: String): String =
        try { bundle.getString(key) } catch (_: MissingResourceException) { key }

    fun format(key: String, vararg args: Any?): String =
        MessageFormat.format(get(key), *args)
}

/**
 * Stable numeric error codes matching the `ErrorCode` enum in reelvault.proto.
 *
 * The daemon embeds these in gRPC trailing metadata (`rv-error-code`) and in
 * `Response.error_code` so clients can show localised messages without parsing
 * English strings returned by the daemon.
 */
object RVErrorCode {
    const val UNKNOWN: Int              = 0
    const val DATABASE: Int             = 1
    const val VIDEO_NOT_FOUND: Int      = 2
    const val TAG_NOT_FOUND: Int        = 3
    const val COLLECTION_NOT_FOUND: Int = 4
    const val METADATA_FAILED: Int      = 5
    const val THUMBNAIL_FAILED: Int     = 6
    const val FILE_NOT_FOUND: Int       = 7
    const val INVALID_PATH: Int         = 8
    const val DUPLICATE_ENTRY: Int      = 9
    const val IO: Int                   = 10
    const val CONFIG: Int               = 11
    const val FFMPEG: Int               = 12
    const val INVALID_REQUEST: Int      = 13
    const val INTERNAL: Int             = 14

    private val TRAILER_KEY =
        Metadata.Key.of("rv-error-code", Metadata.ASCII_STRING_MARSHALLER)

    /** Extract the structured error code from gRPC trailing metadata. */
    fun from(e: StatusException): Int? = e.trailers?.get(TRAILER_KEY)?.toIntOrNull()
    fun from(e: StatusRuntimeException): Int? = e.trailers?.get(TRAILER_KEY)?.toIntOrNull()

    /** A localised, user-facing description for the given code, or null for 0 / unknown. */
    fun localizedDescription(code: Int): String? = when (code) {
        DATABASE             -> Strings["err_code_database"]
        VIDEO_NOT_FOUND      -> Strings["err_code_video_not_found"]
        TAG_NOT_FOUND        -> Strings["err_code_tag_not_found"]
        COLLECTION_NOT_FOUND -> Strings["err_code_collection_not_found"]
        METADATA_FAILED      -> Strings["err_code_metadata_failed"]
        THUMBNAIL_FAILED     -> Strings["err_code_thumbnail_failed"]
        FILE_NOT_FOUND       -> Strings["err_code_file_not_found"]
        INVALID_PATH         -> Strings["err_code_invalid_path"]
        DUPLICATE_ENTRY      -> Strings["err_code_duplicate_entry"]
        IO                   -> Strings["err_code_io"]
        CONFIG               -> Strings["err_code_config"]
        FFMPEG               -> Strings["err_code_ffmpeg"]
        INVALID_REQUEST      -> Strings["err_code_invalid_request"]
        INTERNAL             -> Strings["err_code_internal"]
        else                 -> null
    }
}
