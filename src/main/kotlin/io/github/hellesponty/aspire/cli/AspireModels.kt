package io.github.hellesponty.aspire.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One entry of `aspire ps --format Json`. */
data class RunningAppHost(
    val appHostPath: String,
    val appHostPid: Long?,
    val status: String,
    val dashboardUrl: String?,
    val logFilePath: String?,
) {
    val isRunning: Boolean get() = status.equals("running", ignoreCase = true)
}

data class ResourceUrl(val name: String, val url: String)

data class ResourceCommand(
    val name: String,
    val displayName: String,
    val description: String?,
    val enabled: Boolean,
    val sortOrder: Int,
)

/** One resource from `aspire describe --format Json`. */
data class ResourceInfo(
    val name: String,
    val displayName: String,
    val resourceType: String,
    val state: String?,
    val healthStatus: String?,
    val exitCode: Int?,
    val source: String?,
    val dashboardUrl: String?,
    val urls: List<ResourceUrl>,
    val commands: List<ResourceCommand>,
    val parentName: String?,
    val workDir: String?,
) {
    fun command(name: String): ResourceCommand? = commands.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** One line of `aspire logs --format Json`. */
data class LogLine(val resourceName: String, val content: String, val isError: Boolean, val timestamp: String?)

object AspireJson {
    fun parseOrNull(line: String): JsonElement? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return null
        return try {
            JsonParser.parseString(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    /** `aspire ps` prints an array; `aspire ps --follow` prints one object or one array per line. */
    fun parseAppHosts(element: JsonElement): List<RunningAppHost> = when {
        element.isJsonArray -> element.asJsonArray.mapNotNull { it.asObjOrNull()?.let(::appHost) }
        element.isJsonObject -> listOfNotNull(appHost(element.asJsonObject))
        else -> emptyList()
    }

    private fun appHost(o: JsonObject): RunningAppHost? {
        val path = o.str("appHostPath") ?: return null
        return RunningAppHost(
            appHostPath = path,
            appHostPid = o.long("appHostPid"),
            status = o.str("status") ?: "unknown",
            dashboardUrl = o.str("dashboardUrl"),
            logFilePath = o.str("logFilePath"),
        )
    }

    /** `aspire describe` prints {"resources":[...]}; `--follow` prints one resource object per line. */
    fun parseResources(element: JsonElement): List<ResourceInfo> {
        if (!element.isJsonObject) return emptyList()
        val o = element.asJsonObject
        val arr = o.get("resources")
        if (arr != null && arr.isJsonArray) return arr.asJsonArray.mapNotNull { it.asObjOrNull()?.let(::resource) }
        return listOfNotNull(resource(o))
    }

    private fun resource(o: JsonObject): ResourceInfo? {
        val name = o.str("name") ?: return null
        val props = o.obj("properties")
        val commands = o.obj("commands")?.entrySet()?.mapNotNull { (key, value) ->
            val c = value.asObjOrNull() ?: return@mapNotNull null
            ResourceCommand(
                name = key,
                displayName = c.str("displayName") ?: key,
                description = c.str("description"),
                enabled = c.str("state")?.equals("Enabled", ignoreCase = true) ?: true,
                sortOrder = c.long("sortOrder")?.toInt() ?: Int.MAX_VALUE,
            )
        }?.sortedBy { it.sortOrder } ?: emptyList()
        val urls = o.get("urls")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { u ->
            val uo = u.asObjOrNull() ?: return@mapNotNull null
            val url = uo.str("url") ?: return@mapNotNull null
            ResourceUrl(uo.str("name") ?: url, url)
        } ?: emptyList()
        val parent = props?.str("resource.parentName")
            ?: o.get("relationships")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.asObjOrNull() }
                ?.firstOrNull { it.str("type") == "Parent" }?.str("resourceName")
        return ResourceInfo(
            name = name,
            displayName = o.str("displayName") ?: name,
            resourceType = o.str("resourceType") ?: "Unknown",
            state = o.str("state")?.takeIf { it.isNotBlank() },
            healthStatus = o.str("healthStatus"),
            exitCode = o.long("exitCode")?.toInt(),
            source = o.str("source"),
            dashboardUrl = o.str("dashboardUrl"),
            urls = urls,
            commands = commands,
            parentName = parent,
            workDir = props?.str("executable.workDir"),
        )
    }

    /** `aspire logs --format Json` prints {"logs":[...]}; `--follow` prints one log object per line. */
    fun parseLogs(element: JsonElement): List<LogLine> {
        if (!element.isJsonObject) return emptyList()
        val o = element.asJsonObject
        val arr = o.get("logs")
        if (arr != null && arr.isJsonArray) return arr.asJsonArray.mapNotNull { it.asObjOrNull()?.let(::log) }
        return listOfNotNull(log(o))
    }

    private fun log(o: JsonObject): LogLine? {
        val resource = o.str("resourceName") ?: return null
        return LogLine(resource, o.str("content") ?: "", o.get("isError")?.asBooleanOrNull() ?: false, o.str("timestamp"))
    }

    private fun JsonElement.asObjOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonElement.asBooleanOrNull(): Boolean? = if (isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else null
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.asObjOrNull()
    private fun JsonObject.str(key: String): String? {
        val e = get(key) ?: return null
        return if (e.isJsonPrimitive) e.asString else null
    }
    private fun JsonObject.long(key: String): Long? {
        val e = get(key) ?: return null
        return if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asLong else null
    }
}
