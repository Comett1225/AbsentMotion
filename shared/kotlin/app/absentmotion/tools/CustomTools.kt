package app.absentmotion.tools

import android.content.Context
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.editor.applyPreset
import app.absentmotion.editor.audioLayer
import app.absentmotion.editor.imageLayer
import app.absentmotion.editor.layerBeats
import app.absentmotion.editor.textLayer
import app.absentmotion.editor.videoLayer
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.i18n.AppText
import app.absentmotion.model.FASTEST_SPEED
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.PropRef
import app.absentmotion.model.SLOWEST_SPEED
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.addEffect
import app.absentmotion.model.formatColor
import app.absentmotion.model.formatFloats
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.pinnedPath
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.withNormalSpeed
import app.absentmotion.model.withPinnedClock
import app.absentmotion.model.withSplit
import app.absentmotion.model.withValueAt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

const val TOOLS_API = 1
const val TOOL_JSON_MAX_BYTES = 64L shl 10
const val TOOL_ICON_MAX_BYTES = 256L shl 10
const val TOOL_ICON_FILE = "icon.png"
const val TOOL_FILE = "tool.json"

private const val MAX_INPUTS = 16
private const val MAX_STEPS = 32
private const val MAX_OPTIONS = 20
private const val MAX_TEXT = 500
private const val MAX_LIST = 2000
private const val MAX_SECONDS = 86_400.0
private val TOOL_ID = Regex("^[a-z0-9-]{1,48}$")
private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,31}$")
private val VERSION = Regex("^[0-9]{1,4}(\\.[0-9]{1,4}){0,2}$")
private val COLOUR = Regex("^#[0-9a-fA-F]{6}$")
private val EFFECT_ID = Regex("^absent\\.[a-z0-9_.]{1,48}$")
private val PARAM = Regex("^[A-Za-z0-9_]{1,48}$")

val ToolPermissions = listOf("media.read", "media.write", "project.write")
val ToolPlaces = listOf("home", "editor")

class ToolProblem(message: String) : Exception(message)

enum class ValueKind { Video, Image, Audio, Gif, List, Layer, Number, Text, Switch, Colour, Preset }

private val FileKinds = setOf(ValueKind.Video, ValueKind.Image, ValueKind.Audio, ValueKind.Gif)

class ToolOption(val label: String, val value: Any)

class ToolInput(
    val id: String, val type: String, val label: String, val default: Any?,
    val min: Double = 0.0, val max: Double = 0.0, val step: Double = 0.0, val unit: String = "",
    val options: List<ToolOption> = emptyList(), val maxLength: Int = MAX_TEXT,
) {
    val kind: ValueKind
        get() = when (type) {
            "video" -> ValueKind.Video
            "image" -> ValueKind.Image
            "text" -> ValueKind.Text
            "number" -> ValueKind.Number
            "switch" -> ValueKind.Switch
            "colour" -> ValueKind.Colour
            "layer" -> ValueKind.Layer
            "preset" -> ValueKind.Preset
            else -> when (options.firstOrNull()?.value) {
                is Double -> ValueKind.Number
                is Boolean -> ValueKind.Switch
                else -> ValueKind.Text
            }
        }

    fun number(raw: Double): Double {
        val clamped = raw.coerceIn(min, max)
        return if (step > 0) (min + ((clamped - min) / step).roundToLong() * step).coerceIn(min, max) else clamped
    }
}

class ToolStep(val op: String, val args: JSONObject)

class CustomTool(
    val id: String, val name: String, val version: String, val api: Int, val minVersion: Int,
    val creator: String, val licence: String, val description: String,
    val where: Set<String>, val permissions: List<String>, val inputs: List<ToolInput>, val steps: List<ToolStep>,
) {
    val changesProject: Boolean get() = steps.any { Ops.getValue(it.op).project }
    val effects: List<Pair<String, JSONObject?>> get() = steps.filter { it.op == "applyEffect" }.map { it.args.getString("effect") to it.args.optJSONObject("values") }
}

private sealed interface Arg { val required: Boolean }
private class Ref(val kinds: Set<ValueKind>, override val required: Boolean = true) : Arg
private class Num(val min: Double, val max: Double, override val required: Boolean = true, val whole: Boolean = false) : Arg
private class Word(val words: Set<String>, override val required: Boolean = false, val literal: Boolean = false) : Arg
private class Free(override val required: Boolean = true) : Arg
private class Flag(override val required: Boolean = false) : Arg
private class Paint(override val required: Boolean = false) : Arg
private object PropValue : Arg { override val required = true }
private object EffectValues : Arg { override val required = false }
private object Times : Arg { override val required = true }
private object EffectId : Arg { override val required = true }

private class Op(val project: Boolean, val permissions: Set<String>, val out: ValueKind?, val args: Map<String, Arg>)

private const val READ = "media.read"
private const val WRITE = "media.write"
private const val PROJECT = "project.write"
private val Properties = setOf("opacity", "scale", "rotation", "position", "colour")
private val LayerArg = Ref(setOf(ValueKind.Layer))

private val Ops: Map<String, Op> = mapOf(
    "trim" to Op(false, setOf(READ, WRITE), ValueKind.Video, mapOf("in" to Ref(setOf(ValueKind.Video)), "start" to Num(0.0, MAX_SECONDS), "length" to Num(0.1, MAX_SECONDS, required = false))),
    "compress" to Op(false, setOf(READ, WRITE), null, mapOf("in" to Ref(setOf(ValueKind.Video, ValueKind.Image)), "level" to Word(setOf("low", "medium", "high")))),
    "extractAudio" to Op(false, setOf(READ, WRITE), ValueKind.Audio, mapOf("in" to Ref(setOf(ValueKind.Video)), "format" to Word(setOf("m4a", "mp3")))),
    "gif" to Op(false, setOf(READ, WRITE), ValueKind.Gif, mapOf(
        "in" to Ref(setOf(ValueKind.Video)), "start" to Num(0.0, MAX_SECONDS, required = false), "length" to Num(0.1, MAX_GIF_US / 1e6, required = false),
        "size" to Num(64.0, 720.0, required = false, whole = true), "fps" to Num(1.0, 30.0, required = false, whole = true), "loop" to Flag(),
    )),
    "beats" to Op(false, setOf(READ), ValueKind.List, mapOf("in" to Ref(setOf(ValueKind.Video, ValueKind.Layer)))),
    "every" to Op(false, emptySet(), ValueKind.List, mapOf("list" to Ref(setOf(ValueKind.List)), "step" to Num(1.0, 64.0, whole = true), "offset" to Num(0.0, 1000.0, required = false, whole = true))),
    "addMedia" to Op(true, setOf(PROJECT), ValueKind.Layer, mapOf("in" to Ref(setOf(ValueKind.Video, ValueKind.Image, ValueKind.Audio)))),
    "addText" to Op(true, setOf(PROJECT), ValueKind.Layer, mapOf("text" to Free(), "colour" to Paint(), "length" to Num(0.1, 3600.0, required = false))),
    "setProperty" to Op(true, setOf(PROJECT), null, mapOf("layer" to LayerArg, "property" to Word(Properties, required = true, literal = true), "value" to PropValue)),
    "addKeyframe" to Op(true, setOf(PROJECT), null, mapOf("layer" to LayerArg, "property" to Word(Properties, required = true, literal = true), "at" to Num(-MAX_SECONDS, MAX_SECONDS), "value" to PropValue)),
    "applyEffect" to Op(true, setOf(PROJECT), null, mapOf("layer" to LayerArg, "effect" to EffectId, "values" to EffectValues)),
    "applyPreset" to Op(true, setOf(PROJECT), null, mapOf("layer" to LayerArg, "preset" to Ref(setOf(ValueKind.Preset)))),
    "split" to Op(true, setOf(PROJECT), null, mapOf("layer" to LayerArg, "at" to Times)),
    "speed" to Op(true, setOf(PROJECT), null, mapOf("layer" to LayerArg, "value" to Num(SLOWEST_SPEED.toDouble(), FASTEST_SPEED.toDouble()))),
)

val ToolOps: Set<String> get() = Ops.keys
val ToolInputTypes = listOf("video", "image", "text", "number", "choice", "switch", "colour", "layer", "preset")

fun parseTool(text: String): CustomTool {
    if (text.toByteArray(Charsets.UTF_8).size > TOOL_JSON_MAX_BYTES) problem("tool.json is larger than ${TOOL_JSON_MAX_BYTES / 1024} KB")
    val root = try { JSONObject(text) } catch (e: org.json.JSONException) { problem("tool.json is not valid JSON (${e.message})") }
    val known = setOf("id", "name", "version", "api", "minVersion", "creator", "licence", "description", "icon", "runs", "where", "permissions", "inputs", "steps")
    root.keys().forEach { if (it !in known) problem("unknown field \"$it\"") }
    val id = root.optString("id")
    if (!TOOL_ID.matches(id)) problem("\"id\" must be 1 to 48 of a-z, 0-9 and -")
    val name = line(root, "name", 40, true)
    val version = root.optString("version")
    if (!VERSION.matches(version)) problem("\"version\" must look like 1.0.0")
    val api = root.opt("api") as? Int ?: problem("\"api\" must be a whole number")
    if (api != TOOLS_API) problem("\"api\" $api is not supported (this app runs api $TOOLS_API)")
    val minVersion = if (root.has("minVersion")) root.opt("minVersion") as? Int ?: problem("\"minVersion\" must be a whole number") else 0
    if (root.optString("runs") != "recipe") problem("\"runs\" must be \"recipe\"")
    if (root.has("icon") && root.optString("icon") != TOOL_ICON_FILE) problem("\"icon\" must be \"$TOOL_ICON_FILE\"")
    val where = words(root, "where", ToolPlaces)
    if (where.isEmpty()) problem("\"where\" must name home, editor or both")
    val permissions = words(root, "permissions", ToolPermissions)
    val inputs = parseInputs(root.optJSONArray("inputs") ?: JSONArray())
    val stepsJson = root.optJSONArray("steps") ?: problem("\"steps\" is missing")
    if (stepsJson.length() !in 1..MAX_STEPS) problem("a tool has 1 to $MAX_STEPS steps")
    val steps = List(stepsJson.length()) { i ->
        val step = stepsJson.optJSONObject(i) ?: problem("step ${i + 1} is not an object")
        ToolStep(step.optString("op"), step)
    }
    val tool = CustomTool(
        id, name, version, api, minVersion, line(root, "creator", 80, true), line(root, "licence", 40, false),
        line(root, "description", 500, true), where.toSet(), permissions, inputs, steps,
    )
    checkSteps(tool)
    return tool
}

private fun problem(message: String): Nothing = throw ToolProblem(message)

private fun line(root: JSONObject, key: String, max: Int, required: Boolean): String {
    if (!root.has(key)) { if (required) problem("\"$key\" is missing") else return "" }
    val value = root.opt(key) as? String ?: problem("\"$key\" must be text")
    val trimmed = value.trim()
    if (required && trimmed.isEmpty()) problem("\"$key\" is empty")
    if (trimmed.length > max) problem("\"$key\" is longer than $max characters")
    if (trimmed.any { it.code < 0x20 && it != '\n' }) problem("\"$key\" has control characters")
    return trimmed
}

private fun words(root: JSONObject, key: String, allowed: List<String>): List<String> {
    val array = root.optJSONArray(key) ?: problem("\"$key\" must be a list")
    val out = List(array.length()) { array.opt(it) as? String ?: problem("\"$key\" must hold text") }
    out.forEach { if (it !in allowed) problem("\"$key\" has unknown \"$it\" (known: ${allowed.joinToString()})") }
    if (out.toSet().size != out.size) problem("\"$key\" names something twice")
    return out
}

private fun parseInputs(array: JSONArray): List<ToolInput> {
    if (array.length() > MAX_INPUTS) problem("a tool has at most $MAX_INPUTS inputs")
    val seen = HashSet<String>()
    return List(array.length()) { i ->
        val o = array.optJSONObject(i) ?: problem("input ${i + 1} is not an object")
        val id = o.optString("id")
        if (!NAME.matches(id)) problem("input ${i + 1}: \"id\" must start with a letter and use only letters, digits and _")
        if (!seen.add(id)) problem("input \"$id\" is named twice")
        val type = o.optString("type")
        if (type !in ToolInputTypes) problem("input \"$id\": unknown type \"$type\" (known: ${ToolInputTypes.joinToString()})")
        val allowed = setOf("id", "type", "label", "default") + when (type) {
            "number" -> setOf("min", "max", "step", "unit")
            "choice" -> setOf("options")
            "text" -> setOf("maxLength")
            else -> emptySet()
        }
        o.keys().forEach { if (it !in allowed) problem("input \"$id\": unknown field \"$it\"") }
        val label = line(o, "label", 40, true)
        val default = o.opt("default")
        fun bad(what: String): Nothing = problem("input \"$id\": $what")
        when (type) {
            "number" -> {
                val min = number(o, "min") ?: bad("\"min\" must be a number")
                val max = number(o, "max") ?: bad("\"max\" must be a number")
                if (min >= max) bad("\"min\" must be below \"max\"")
                val step = if (o.has("step")) number(o, "step")?.takeIf { it > 0 } ?: bad("\"step\" must be a number above 0") else 0.0
                val start = if (default == null) min else (default as? Number)?.toDouble() ?: bad("\"default\" must be a number")
                if (start !in min..max) bad("\"default\" must be from \"min\" to \"max\"")
                ToolInput(id, type, label, start, min, max, step, line(o, "unit", 12, false))
            }
            "choice" -> {
                val list = o.optJSONArray("options") ?: bad("\"options\" must be a list")
                if (list.length() !in 1..MAX_OPTIONS) bad("a choice has 1 to $MAX_OPTIONS options")
                val options = List(list.length()) { k ->
                    when (val raw = list.opt(k)) {
                        is String -> ToolOption(raw.trim().take(40).ifEmpty { bad("option ${k + 1} is empty") }, k.toDouble())
                        is JSONObject -> {
                            val text = raw.opt("label") as? String ?: bad("option ${k + 1} needs a \"label\"")
                            val v: Any? = raw.opt("value")
                            val value: Any = if (v is Number) v.toDouble()
                                else if (v is String) v.also { if (it.length > MAX_TEXT) bad("option ${k + 1} value is too long") }
                                else if (v is Boolean) v
                                else bad("option ${k + 1} needs a number, text or true/false \"value\"")
                            ToolOption(text.trim().take(40).ifEmpty { bad("option ${k + 1} is empty") }, value)
                        }
                        else -> bad("option ${k + 1} must be text or {\"label\", \"value\"}")
                    }
                }
                if (options.map { it.value.javaClass }.toSet().size != 1) bad("all options need the same kind of value")
                val index = if (default == null) 0 else default as? Int ?: bad("\"default\" must be the number of an option, from 0")
                if (index !in options.indices) bad("\"default\" must be from 0 to ${options.size - 1}")
                ToolInput(id, type, label, index, options = options)
            }
            "switch" -> ToolInput(id, type, label, if (default == null) false else default as? Boolean ?: bad("\"default\" must be true or false"))
            "text" -> {
                val max = if (o.has("maxLength")) (o.opt("maxLength") as? Int)?.takeIf { it in 1..MAX_TEXT } ?: bad("\"maxLength\" must be 1 to $MAX_TEXT") else MAX_TEXT
                val start = if (default == null) "" else default as? String ?: bad("\"default\" must be text")
                if (start.length > max || '\n' in start) bad("\"default\" is longer than \"maxLength\" or has a line break")
                ToolInput(id, type, label, start, maxLength = max)
            }
            "colour" -> {
                val start = if (default == null) "#ffffff" else default as? String ?: bad("\"default\" must be a colour like #ff8800")
                if (!COLOUR.matches(start)) bad("\"default\" must be a colour like #ff8800")
                ToolInput(id, type, label, start.lowercase(Locale.ROOT))
            }
            else -> {
                if (default != null) bad("a $type input has no \"default\"")
                ToolInput(id, type, label, null)
            }
        }
    }
}

private fun number(o: JSONObject, key: String): Double? = (o.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

private class Scope(val tool: CustomTool) {
    val kinds = HashMap<String, ValueKind>().apply { tool.inputs.forEach { put(it.id, it.kind) } }
    fun input(name: String) = tool.inputs.firstOrNull { it.id == name }
}

private fun checkSteps(tool: CustomTool) {
    val scope = Scope(tool)
    val needed = HashSet<String>()
    if (tool.inputs.any { it.kind == ValueKind.Video || it.kind == ValueKind.Image }) needed += READ
    var makes = false
    tool.steps.forEachIndexed { i, step ->
        val where = "step ${i + 1} (${step.op.ifEmpty { "no op" }})"
        fun bad(what: String): Nothing = problem("$where: $what")
        val op = Ops[step.op] ?: problem("step ${i + 1}: unknown op \"${step.op}\" (known: ${Ops.keys.joinToString()})")
        step.args.keys().forEach { if (it != "op" && it != "out" && it !in op.args) bad("unknown field \"$it\"") }
        needed += op.permissions
        if (op.project) makes = true
        for ((key, arg) in op.args) {
            if (!step.args.has(key)) { if (arg.required) bad("\"$key\" is missing") else continue }
            checkArg(scope, step, key, arg, ::bad)
        }
        val outKind = op.out ?: if (step.op == "compress") scope.kinds[ref(step.args.opt("in"))] else null
        if (outKind in FileKinds) makes = true
        if (step.args.has("out")) {
            val out = step.args.opt("out") as? String ?: bad("\"out\" must be a name")
            if (outKind == null) bad("this op has no \"out\"")
            if (!NAME.matches(out)) bad("\"out\" must start with a letter and use only letters, digits and _")
            if (out in scope.kinds) bad("\"$out\" is already used")
            scope.kinds[out] = outKind
        }
    }
    if (!makes) problem("the tool makes nothing: it needs a step that makes a file or changes the project")
    val editorOnly = tool.changesProject || tool.inputs.any { it.kind == ValueKind.Layer || it.kind == ValueKind.Preset }
    if (editorOnly && tool.where != setOf("editor")) problem("a tool that changes the project or asks for a layer or preset runs only in the editor: \"where\" must be [\"editor\"]")
    val missing = needed - tool.permissions.toSet()
    if (missing.isNotEmpty()) problem("\"permissions\" must also name ${missing.sorted().joinToString()}")
    val unused = tool.permissions.toSet() - needed
    if (unused.isNotEmpty()) problem("\"permissions\" names ${unused.sorted().joinToString()} but no step needs it")
}

private fun ref(value: Any?): String? = (value as? String)?.removePrefix("$")

private fun checkArg(scope: Scope, step: ToolStep, key: String, arg: Arg, bad: (String) -> Nothing) {
    val value = step.args.opt(key)
    val named = (value as? String)?.takeIf { it.startsWith("$") }?.drop(1)
    fun refKind(name: String): ValueKind = scope.kinds[name] ?: bad("\"$key\" names \"$name\", which is no input or earlier \"out\"")
    fun choiceValues(name: String): List<Any>? = scope.input(name)?.takeIf { it.type == "choice" }?.options?.map { it.value }
    when (arg) {
        is Ref -> {
            val name = ref(value) ?: bad("\"$key\" must name an input or an earlier \"out\"")
            val kind = refKind(name)
            if (kind !in arg.kinds) bad("\"$key\" needs ${arg.kinds.joinToString(" or ") { it.name.lowercase(Locale.ROOT) }}, but \"$name\" is ${kind.name.lowercase(Locale.ROOT)}")
        }
        is Num -> numberArg(value, named, arg.min, arg.max, arg.whole, key, ::refKind, ::choiceValues, scope, bad)
        is Word -> when {
            named != null && !arg.literal -> {
                val options = choiceValues(named) ?: bad("\"$key\" can only name a choice input")
                options.forEach { if (it !in arg.words) bad("\"$key\": option value \"$it\" must be one of ${arg.words.joinToString()}") }
            }
            value !is String || value !in arg.words -> bad("\"$key\" must be one of ${arg.words.joinToString()}")
        }
        is Free -> when {
            named != null -> if (refKind(named) != ValueKind.Text) bad("\"$key\" must name a text input or a choice with text values")
            value !is String || value.length > MAX_TEXT -> bad("\"$key\" must be text of at most $MAX_TEXT characters")
        }
        is Flag -> when {
            named != null -> if (refKind(named) != ValueKind.Switch) bad("\"$key\" must name a switch")
            value !is Boolean -> bad("\"$key\" must be true or false")
        }
        is Paint -> when {
            named != null -> if (refKind(named) != ValueKind.Colour) bad("\"$key\" must name a colour input")
            value !is String || !COLOUR.matches(value) -> bad("\"$key\" must be a colour like #ff8800")
        }
        PropValue -> {
            val property = step.args.optString("property")
            when (property) {
                "colour" -> checkArg(scope, step, key, Paint(true), bad)
                "position" -> {
                    val pair = value as? JSONArray ?: bad("\"$key\" for position must be [x, y]")
                    if (pair.length() != 2) bad("\"$key\" for position must be [x, y]")
                    for (k in 0..1) {
                        val part = pair.opt(k)
                        numberArg(part, (part as? String)?.takeIf { it.startsWith("$") }?.drop(1), -100_000.0, 100_000.0, false, key, ::refKind, ::choiceValues, scope, bad)
                    }
                }
                "opacity" -> numberArg(value, named, 0.0, 100.0, false, key, ::refKind, ::choiceValues, scope, bad)
                "scale" -> numberArg(value, named, 0.0, 10_000.0, false, key, ::refKind, ::choiceValues, scope, bad)
                else -> numberArg(value, named, -36_000.0, 36_000.0, false, key, ::refKind, ::choiceValues, scope, bad)
            }
        }
        EffectId -> if (value !is String || !EFFECT_ID.matches(value)) bad("\"$key\" must be an effect id like absent.blur")
        EffectValues -> {
            val values = value as? JSONObject ?: bad("\"$key\" must be an object of effect settings")
            if (values.length() > 32) bad("\"$key\" has more than 32 settings")
            values.keys().forEach { name ->
                if (!PARAM.matches(name)) bad("\"$key\": \"$name\" is not a setting name")
                val v: Any? = values.opt(name)
                when (v) {
                    is Number, is Boolean -> Unit
                    is String -> if (v.startsWith("$")) {
                        if (refKind(v.drop(1)) !in setOf(ValueKind.Number, ValueKind.Switch, ValueKind.Colour)) bad("\"$key\": \"$name\" must name a number, switch or colour input")
                    } else if (!COLOUR.matches(v)) bad("\"$key\": \"$name\" must be a number, true/false, a colour or [x, y]")
                    is JSONArray -> if (v.length() != 2 || (0..1).any { v.opt(it) !is Number }) bad("\"$key\": \"$name\" must be [x, y]")
                    else -> bad("\"$key\": \"$name\" must be a number, true/false, a colour or [x, y]")
                }
            }
        }
        Times -> when {
            named != null || value is String -> if (refKind(ref(value)!!) != ValueKind.List && refKind(ref(value)!!) != ValueKind.Number) bad("\"$key\" must name a list of times or a number input")
            value !is Number || value.toDouble() !in 0.0..MAX_SECONDS -> bad("\"$key\" must be seconds from 0, or name a list of times")
        }
    }
}

private fun numberArg(
    value: Any?, named: String?, min: Double, max: Double, whole: Boolean, key: String,
    refKind: (String) -> ValueKind, choiceValues: (String) -> List<Any>?, scope: Scope, bad: (String) -> Nothing,
) {
    fun fits(v: Double) = v in min..max && (!whole || v == Math.rint(v))
    val range = "from ${fmt(min)} to ${fmt(max)}${if (whole) ", whole" else ""}"
    if (named != null) {
        if (refKind(named) != ValueKind.Number) bad("\"$key\" must name a number input or a choice with number values")
        val input = scope.input(named)
        val choices = choiceValues(named)
        when {
            choices != null -> choices.forEach { if (!fits((it as Double))) bad("\"$key\": option value ${fmt(it)} must be $range") }
            input != null -> if (input.min < min || input.max > max || whole && (input.step != Math.rint(input.step) || input.step == 0.0 || input.min != Math.rint(input.min))) bad("\"$key\": input \"$named\" must stay $range")
        }
        return
    }
    val number = (value as? Number)?.toDouble() ?: bad("\"$key\" must be a number or \"\$input\"")
    if (!fits(number)) bad("\"$key\" must be $range")
}

private fun fmt(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

fun checkEffects(tool: CustomTool, find: (String) -> EffectDefinition?) {
    val inputs = tool.inputs.associateBy { it.id }
    for ((id, values) in tool.effects) {
        val definition = find(id)?.takeIf { it.available } ?: problem("this app has no effect \"$id\"")
        values?.keys()?.forEach { name ->
            val param = definition.params.firstOrNull { it.id == name } ?: problem("effect \"$id\" has no setting \"$name\"")
            val v = values.opt(name)
            val kind = (v as? String)?.takeIf { it.startsWith("$") }?.let { inputs[it.drop(1)]?.kind }
            val ok = when (param.kind) {
                "switch" -> v is Boolean || kind == ValueKind.Switch
                "color" -> v is String && (kind == ValueKind.Colour || COLOUR.matches(v))
                "point" -> v is JSONArray
                "text" -> false
                "choice" -> (v as? Number)?.toDouble()?.let { it == Math.rint(it) && it.toInt() in param.options.indices } ?: (kind == ValueKind.Number)
                else -> v is Number || kind == ValueKind.Number
            }
            if (!ok) problem("effect \"$id\": \"$name\" needs a ${param.kind} value")
        }
    }
}

interface ToolHost {
    suspend fun read(file: Compressed): PickedMedia
    suspend fun trim(media: PickedMedia, range: TrimRange, progress: (Float) -> Unit): Compressed
    suspend fun compress(media: PickedMedia, level: CompressLevel, progress: (Float) -> Unit): Compressed
    suspend fun extractAudio(media: PickedMedia, mp3: Boolean, progress: (Float) -> Unit): Compressed
    suspend fun gif(media: PickedMedia, choice: GifChoice, range: TrimRange, progress: (Float) -> Unit): Compressed
    suspend fun beats(media: PickedMedia): List<Double>
    suspend fun beats(scene: Scene, layer: Layer): List<Double>
    suspend fun declare(file: Any, kind: ValueKind): MediaDeclaration
    fun effect(id: String): EffectDefinition?
    fun preset(id: String): Layer?
    val layerSeconds: Int
}

class ToolRun(val result: Compressed?, val edits: List<(Scene) -> Scene>)

suspend fun runTool(tool: CustomTool, inputs: Map<String, Any>, host: ToolHost, scene: Scene?, frame: Int, folder: File?, progress: (Float) -> Unit): ToolRun {
    val vars = HashMap<String, Any>()
    for (input in tool.inputs) {
        val raw = inputs[input.id] ?: input.default ?: throw ToolProblem(AppText.get(R.string.custom_tool_missing, input.label))
        vars[input.id] = when (input.type) {
            "number" -> input.number((raw as Number).toDouble())
            "choice" -> input.options[(raw as Number).toInt().coerceIn(input.options.indices)].value
            "text" -> (raw as String).take(input.maxLength)
            "colour" -> (raw as String).let { if (COLOUR.matches(it)) it.lowercase(Locale.ROOT) else input.default as String }
            else -> raw
        }
    }
    var working = scene
    val edits = ArrayList<(Scene) -> Scene>()
    val made = ArrayList<Compressed>()
    var result: Compressed? = null
    val count = tool.steps.size
    fun project(change: (Scene) -> Scene) {
        val now = working ?: throw ToolProblem(AppText.get(R.string.custom_tool_needs_editor))
        working = change(now)
        edits += change
    }
    fun layer(name: String?): Layer {
        val id = vars[name!!] as Long
        return working?.layer(id) ?: throw ToolProblem(AppText.get(R.string.custom_tool_layer_gone))
    }
    fun keep(file: Compressed): Compressed {
        val moved = if (folder == null) file else {
            folder.mkdirs()
            val target = File(folder, file.file.name)
            if (target.exists()) target.delete()
            if (!file.file.renameTo(target)) { file.file.copyTo(target, overwrite = true); file.file.delete() }
            Compressed(target, file.mime, file.video, file.frames)
        }
        made += moved
        result = moved
        return moved
    }
    for ((index, step) in tool.steps.withIndex()) {
        currentCoroutineContext().ensureActive()
        val args = step.args
        val part: (Float) -> Unit = { progress((index + it.coerceIn(0f, 1f)) / count) }
        suspend fun media(key: String): PickedMedia = when (val v = vars[ref(args.opt(key))!!]) {
            is PickedMedia -> v
            is Compressed -> host.read(v)
            else -> throw ToolProblem(AppText.get(R.string.compress_unreadable))
        }
        fun num(key: String, fallback: Double): Double {
            val v: Any? = args.opt(key)
            return if (v is Number) v.toDouble() else if (v is String) (vars[v.drop(1)] as Number).toDouble() else fallback
        }
        fun word(key: String, fallback: String): String {
            val v: Any? = args.opt(key)
            return if (v is String) (if (v.startsWith("$")) vars[v.drop(1)] as String else v) else fallback
        }
        fun flag(key: String, fallback: Boolean): Boolean {
            val v: Any? = args.opt(key)
            return if (v is Boolean) v else if (v is String) vars[v.drop(1)] as Boolean else fallback
        }
        fun colour(value: Any?): Int = parseHex((if (value is String && value.startsWith("$")) vars[value.drop(1)] else value) as String)
        val out: Any? = when (step.op) {
            "trim" -> {
                val m = media("in")
                val start = (num("start", 0.0) * 1e6).toLong()
                if (start >= m.source.durationUs) throw ToolProblem(AppText.get(R.string.custom_tool_after_end))
                val end = if (args.has("length")) minOf(m.source.durationUs, start + (num("length", 0.0) * 1e6).toLong()) else m.source.durationUs
                keep(host.trim(m, TrimRange(start, maxOf(end, start + MIN_CUT_US).coerceAtMost(m.source.durationUs)), part))
            }
            "compress" -> keep(host.compress(media("in"), when (word("level", "medium")) { "low" -> CompressLevel.Low; "high" -> CompressLevel.High; else -> CompressLevel.Medium }, part))
            "extractAudio" -> keep(host.extractAudio(media("in"), word("format", "m4a") == "mp3", part))
            "gif" -> {
                val m = media("in")
                val start = (num("start", 0.0) * 1e6).toLong()
                if (start >= m.source.durationUs) throw ToolProblem(AppText.get(R.string.custom_tool_after_end))
                val end = minOf(m.source.durationUs, start + (num("length", MAX_GIF_US / 1e6) * 1e6).toLong())
                keep(host.gif(m, GifChoice(num("size", 480.0).roundToInt(), num("fps", 15.0).roundToInt(), flag("loop", true)), TrimRange(start, maxOf(end, start + 1)), part))
            }
            "beats" -> (if (vars[ref(args.opt("in"))!!] is Long) host.beats(working!!, layer(ref(args.opt("in")))) else host.beats(media("in"))).take(MAX_LIST)
            "every" -> {
                @Suppress("UNCHECKED_CAST") val list = vars[ref(args.opt("list"))!!] as List<Double>
                val step2 = num("step", 1.0).roundToInt().coerceAtLeast(1)
                val offset = num("offset", 0.0).roundToInt().coerceAtLeast(0)
                list.filterIndexed { i, _ -> i >= offset && (i - offset) % step2 == 0 }
            }
            "addMedia" -> {
                val source = vars[ref(args.opt("in"))!!]!!
                val kind = (tool.inputs.firstOrNull { it.id == ref(args.opt("in")) }?.kind) ?: when ((source as Compressed).mime.substringBefore('/')) {
                    "audio" -> ValueKind.Audio
                    "video" -> ValueKind.Video
                    else -> ValueKind.Image
                }
                val declared = host.declare(source, kind)
                val now = working ?: throw ToolProblem(AppText.get(R.string.custom_tool_needs_editor))
                val added = when (kind) {
                    ValueKind.Video -> now.videoLayer(declared, frame)
                    ValueKind.Audio -> now.audioLayer(declared, frame)
                    else -> now.imageLayer(declared, frame, host.layerSeconds)
                } ?: throw ToolProblem(AppText.get(R.string.compress_unreadable))
                project { it.add(added, listOf(declared)) }
                added.id
            }
            "addText" -> {
                val now = working ?: throw ToolProblem(AppText.get(R.string.custom_tool_needs_editor))
                val added = now.textLayer(frame, (num("length", host.layerSeconds.toDouble()) * 1000).roundToInt(), word("text", ""), if (args.has("colour")) colour(args.opt("colour")) else null)
                project { it.add(added) }
                added.id
            }
            "setProperty", "addKeyframe" -> {
                val target = layer(ref(args.opt("layer")))
                val property = args.getString("property")
                val value = propertyValue(target, property, args.opt("value")) { v -> if (v is String && v.startsWith("$")) vars[v.drop(1)] else v }
                val ref = if (property == "colour") PropRef.FillColor else PropRef(PropRef.Kind.Transform, if (property == "position") "location" else property)
                val offset = (num("at", 0.0) * 1000).roundToInt()
                val time = if (step.op == "setProperty") working!!.frameTime(frame) else if (offset < 0) target.endTime + offset else target.startTime + offset
                val at = time.coerceIn(target.startTime, target.endTime)
                val keyed = step.op == "addKeyframe"
                project { s -> s.update(target.id, at) { l -> l.withToolValue(ref, value, keyed) } }
                null
            }
            "applyEffect" -> {
                val target = layer(ref(args.opt("layer")))
                val definition = host.effect(args.getString("effect"))?.takeIf { it.available } ?: throw ToolProblem(AppText.get(R.string.custom_tool_cannot_run, args.getString("effect")))
                val node = effectNode(definition, args.optJSONObject("values")) { v -> if (v is String && v.startsWith("$")) vars[v.drop(1)] else v }
                project { s -> s.update(target.id) { it.addEffect(node) } }
                null
            }
            "applyPreset" -> {
                val target = layer(ref(args.opt("layer")))
                val id = vars[ref(args.opt("preset"))!!] as String
                val preset = host.preset(id) ?: throw ToolProblem(AppText.get(R.string.custom_tool_no_presets))
                project { s -> s.update(target.id) { applyPreset(it, preset, id) } }
                null
            }
            "split" -> {
                val target = layer(ref(args.opt("layer")))
                val now = working!!
                val at: Any? = args.opt("at")
                val named = if (at is Number) null else vars[ref(at)!!]
                @Suppress("UNCHECKED_CAST") val seconds = if (at is Number) listOf(at.toDouble()) else if (named is Double) listOf(named) else named as List<Double>
                val first = now.frameAt(target.startTime)
                val last = now.frameAt(target.endTime)
                val frames = seconds.map { now.frameAt(target.startTime + (it * 1000).roundToInt()) }.filter { it > first && it <= last }.distinct().sortedDescending()
                if (frames.isNotEmpty()) project { s -> frames.fold(s) { acc, f -> acc.layer(target.id)?.let { l -> acc.withSplit(acc.pinnedPath(l).withPinnedClock(), f, Layer.newId()) } ?: acc } }
                null
            }
            "speed" -> {
                val target = layer(ref(args.opt("layer")))
                if (target.kind != "audio" && target.mediaFill?.video != true) throw ToolProblem(AppText.get(R.string.custom_tool_needs_video_layer))
                val speed = num("value", 1.0).toFloat()
                project { s -> s.update(target.id) { it.withNormalSpeed(speed) } }
                null
            }
            else -> throw ToolProblem(AppText.get(R.string.custom_tool_cannot_run, step.op))
        }
        if (out != null) args.optString("out").takeIf { it.isNotEmpty() }?.let { vars[it] = out }
        progress((index + 1f) / count)
    }
    val final = result.takeIf { edits.isEmpty() }
    made.filter { it !== final }.forEach { it.file.delete() }
    return ToolRun(final, edits)
}

private fun parseHex(text: String): Int = (0xFF000000L or text.removePrefix("#").toLong(16)).toInt()

private fun propertyValue(layer: Layer, property: String, raw: Any?, resolve: (Any?) -> Any?): String = when (property) {
    "colour" -> formatColor(parseHex(resolve(raw) as String))
    "position" -> {
        val pair = raw as JSONArray
        val now = layer.location.copyOf()
        now[0] = (resolve(pair.opt(0)) as Number).toFloat()
        now[1] = (resolve(pair.opt(1)) as Number).toFloat()
        formatFloats(now)
    }
    "opacity" -> formatFloats(floatArrayOf(((resolve(raw) as Number).toFloat() / 100f).coerceIn(0f, 1f)))
    "scale" -> {
        val factor = (resolve(raw) as Number).toFloat() / 100f
        val now = layer.scale.copyOf()
        now[0] = factor
        now[1] = factor
        formatFloats(now)
    }
    else -> formatFloats(floatArrayOf(-(resolve(raw) as Number).toFloat()))
}

private fun Layer.withToolValue(ref: PropRef, value: String, keyed: Boolean): Layer {
    val element = element(ref) ?: XmlNode(ref.name, mapOf("value" to value))
    val next = when {
        !keyed -> element.withValueAt(t, value, snap)
        element.isKeyframed -> element.withValueAt(t, value, snap)
        else -> element.withKeyframes(listOf(Keyframe(t, value)))
    }
    return withElement(ref, next)
}

private fun effectNode(definition: EffectDefinition, values: JSONObject?, resolve: (Any?) -> Any?): XmlNode {
    val plain = definition.instance()
    if (values == null) return plain
    return plain.copy(children = plain.children.map { node ->
        val param = definition.params.firstOrNull { it.id == node["name"] }
        if (param == null || !values.has(param.id)) node
        else when (val v = resolve(values.opt(param.id))) {
            is Number -> node.with("value", EffectParam.format(param.id, param.kind, v.toDouble().coerceIn(param.min.toDouble(), param.max.toDouble())))
            else -> node.with("value", EffectParam.format(param.id, param.kind, v))
        }
    })
}

class InstalledTool(val tool: CustomTool, val folder: File) {
    val icon: File get() = File(folder, TOOL_ICON_FILE)
    val file: File get() = File(folder, TOOL_FILE)
}

object InstalledTools {
    var revision by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun root(context: Context): File = File(context.filesDir, "tools")

    fun list(context: Context): List<InstalledTool> = list(root(context)).filter { it.tool.minVersion <= app.absentmotion.BuildConfig.VERSION_CODE }

    internal fun list(root: File): List<InstalledTool> = root.listFiles().orEmpty().filter { it.isDirectory && TOOL_ID.matches(it.name) }.mapNotNull { dir ->
        runCatching {
            val file = File(dir, TOOL_FILE)
            if (!file.isFile || file.length() > TOOL_JSON_MAX_BYTES) return@runCatching null
            InstalledTool(parseTool(file.readText()), dir).takeIf { it.tool.id == dir.name }
        }.getOrNull()
    }.sortedBy { it.tool.name.lowercase(Locale.ROOT) }

    fun find(context: Context, id: String): InstalledTool? = list(context).firstOrNull { it.tool.id == id }

    fun install(context: Context, id: String, json: File, icon: File?): CustomTool = install(root(context), id, json, icon).also { revision++ }

    internal fun install(root: File, id: String, json: File, icon: File?): CustomTool {
        val tool = parseTool(json.readText())
        if (tool.id != id) throw ToolProblem("tool.json has id \"${tool.id}\", expected \"$id\"")
        root.mkdirs()
        val staging = File(root, ".$id.new")
        clear(staging)
        staging.mkdirs()
        json.copyTo(File(staging, TOOL_FILE), overwrite = true)
        icon?.copyTo(File(staging, TOOL_ICON_FILE), overwrite = true)
        val target = File(root, id)
        clear(target)
        if (!staging.renameTo(target)) { clear(staging); throw ToolProblem("could not install the tool") }
        return tool
    }

    fun remove(context: Context, id: String) {
        remove(root(context), id)
        revision++
    }

    internal fun remove(root: File, id: String) {
        if (TOOL_ID.matches(id)) clear(File(root, id))
    }

    private fun clear(dir: File) {
        if (java.nio.file.Files.isSymbolicLink(dir.toPath())) { dir.delete(); return }
        dir.listFiles()?.forEach { if (java.nio.file.Files.isSymbolicLink(it.toPath()) || it.isFile) it.delete() }
        dir.delete()
    }
}

fun toolRunFolder(context: Context, id: String): File {
    val folder = File(compressFolder(context).parentFile, "tool-runs/$id")
    folder.listFiles()?.forEach { if (java.nio.file.Files.isSymbolicLink(it.toPath()) || it.isFile) it.delete() }
    return folder
}

class AppToolHost(private val context: Context, override val layerSeconds: Int, private val effects: (String) -> EffectDefinition?, private val keepMedia: (suspend (MediaDeclaration, Boolean) -> MediaDeclaration)?) : ToolHost {
    override suspend fun read(file: Compressed): PickedMedia =
        readPicked(context, androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file.file)) ?: throw ToolProblem(AppText.get(R.string.compress_unreadable))

    override suspend fun trim(media: PickedMedia, range: TrimRange, progress: (Float) -> Unit) = trimVideo(context, media, TrimJob(range, false), progress)

    override suspend fun compress(media: PickedMedia, level: CompressLevel, progress: (Float) -> Unit): Compressed =
        if (media.source.video) compressVideo(context, media, planVideo(media.source, level)) { f, _ -> progress(f) } else compressPicture(context, media, planPicture(level))

    override suspend fun extractAudio(media: PickedMedia, mp3: Boolean, progress: (Float) -> Unit) =
        extractAudio(context, media, planAudio(media.source, ExtractChoice(mp3, if (mp3) SoundKbps[1] else 0)), progress)

    override suspend fun gif(media: PickedMedia, choice: GifChoice, range: TrimRange, progress: (Float) -> Unit) =
        makeGif(context, media, planGif(media.source, ((media.rotation % 360) + 360) % 360 / 90 * 90, choice, range), progress)

    override suspend fun beats(media: PickedMedia): List<Double> {
        val ms = (media.source.durationUs / 1000).toInt().coerceAtLeast(1)
        val uri = media.uri.toString()
        val layer = Layer(XmlNode("audio", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "$ms", "src" to uri, "outTime" to "$ms", "mediaFillMode" to "fill")))
        return beats(Scene.create("", 16, 16, 30, 0).add(layer), layer)
    }

    override suspend fun beats(scene: Scene, layer: Layer): List<Double> = layerBeats(context, scene, layer).map { it / 1000.0 }

    override suspend fun declare(file: Any, kind: ValueKind): MediaDeclaration {
        val uri = when (file) { is PickedMedia -> file.uri; is Compressed -> Uri.fromFile(file.file); else -> null } ?: throw ToolProblem(AppText.get(R.string.compress_unreadable))
        val declared = when (kind) {
            ValueKind.Audio -> app.absentmotion.audio.audioDeclaration(context, uri)
            ValueKind.Video -> app.absentmotion.media.videoDeclaration(context, uri)
            else -> app.absentmotion.media.imageDeclaration(context, uri)
        } ?: throw ToolProblem(AppText.get(R.string.compress_unreadable))
        return keepMedia?.invoke(declared, file is Compressed) ?: declared
    }

    override fun effect(id: String) = effects(id)

    override fun preset(id: String) = app.absentmotion.library.PresetStore(context).load(id)?.layer
}
