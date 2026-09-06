package com.keyscan.desktop

import com.keyscan.core.model.AppLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Reads the reviewed Android translations copied by tools/Import-AndroidStrings.ps1. */
internal object AndroidStringCatalog {
    private val catalogs = ConcurrentHashMap<String, Map<String, String>>()

    fun text(language: AppLanguage, key: String, vararg arguments: Any): String {
        val tag = resolvedLanguage(language).tag.takeUnless { it == "system" } ?: "en"
        val template = catalog(tag)[key] ?: catalog("en")[key]
            ?: error("Android string is missing in both $tag and English: $key")
        val decoded = template.androidUnescape()
        return if (arguments.isEmpty()) decoded else String.format(Locale.ROOT, decoded, *arguments)
    }

    fun contains(language: AppLanguage, key: String): Boolean {
        val tag = resolvedLanguage(language).tag.takeUnless { it == "system" } ?: "en"
        return key in catalog(tag) || key in catalog("en")
    }

    fun containsDirect(language: AppLanguage, key: String): Boolean {
        val tag = resolvedLanguage(language).tag.takeUnless { it == "system" } ?: "en"
        return key in catalog(tag)
    }

    private fun catalog(tag: String): Map<String, String> = catalogs.computeIfAbsent(tag) {
        val resource = "/android-strings/$tag.json"
        val json = checkNotNull(javaClass.getResourceAsStream(resource)) { "Missing Android string catalog: $resource" }
            .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        Json.parseToJsonElement(json).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
    }

    private fun String.androidUnescape(): String = replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"")
}
