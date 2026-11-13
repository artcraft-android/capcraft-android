package io.github.artcraftandroid.capcraft.ui.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale
import org.json.JSONObject

/**
 * One modular UI language pack, loaded either from `assets/lang/<code.json>` (built-in) or from
 * `filesDir/lang-packs/<name.json>` (user-imported community JSON packs).
 */
data class LanguagePack(
    val id: String,
    val code: String,
    val name: String,
    val nativeName: String,
    val rtl: Boolean,
    val builtIn: Boolean,
    val strings: Map<String, String>,
    val fileName: String? = null,
)

val EmptyLanguagePack = LanguagePack(
    id = "builtin:en",
    code = "en",
    name = "English",
    nativeName = "English",
    rtl = false,
    builtIn = true,
    strings = emptyMap(),
)

val LocalLanguagePack = compositionLocalOf { EmptyLanguagePack }

/** Looks up a UI string in the active [LanguagePack], falling back cleanly to [key] (English). */
@Composable
@ReadOnlyComposable
fun tr(key: String): String = LocalLanguagePack.current.strings[key] ?: key

/** Looks up a formatted UI string in the active [LanguagePack], falling back safely on bad placeholders. */
@Composable
@ReadOnlyComposable
fun trf(key: String, vararg args: Any): String {
    val template = LocalLanguagePack.current.strings[key] ?: key
    return runCatching { String.format(Locale.getDefault(), template, *args) }
        .getOrElse { runCatching { String.format(Locale.US, key, *args) }.getOrDefault(template) }
}

/**
 * Registry and persistence for CapCraft's built-in (`en`, `ja`, `ar`) and custom `.json` language
 * packs (#2, #14, #22).
 */
object AppLanguage {
    const val SYSTEM_ID = "system"
    private const val PREFS_NAME = "capcraft_prefs"
    private const val PREF_KEY = "language_pack_id"
    private const val CUSTOM_DIR = "lang-packs"
    private val BUILT_IN_FILES = listOf("en.json", "ja.json", "ar.json")
    private val RTL_CODES = setOf("ar", "fa", "he", "iw", "ur", "ps", "sd", "ug", "yi")
    private val META_KEYS = setOf("code", "name", "nativeName", "rtl", "strings")

    @Volatile
    private var initialized = false

    var selectedId by mutableStateOf(SYSTEM_ID)
        private set
    var builtInPacks by mutableStateOf<List<LanguagePack>>(emptyList())
        private set
    var customPacks by mutableStateOf<List<LanguagePack>>(emptyList())
        private set

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            selectedId = prefs.getString(PREF_KEY, SYSTEM_ID) ?: SYSTEM_ID
            builtInPacks = BUILT_IN_FILES.mapNotNull { asset ->
                runCatching {
                    val raw = app.assets.open("lang/$asset").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    parsePack(raw, id = "builtin:${asset.removeSuffix(".json")}", builtIn = true, fileName = asset)
                }.getOrNull()
            }
            reloadCustomPacks(app)
            initialized = true
        }
    }

    private fun reloadCustomPacks(app: Context) {
        val dir = File(app.filesDir, CUSTOM_DIR)
        if (!dir.isDirectory) {
            customPacks = emptyList()
            return
        }
        customPacks = dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .mapNotNull { file ->
                runCatching {
                    val raw = file.readText(Charsets.UTF_8)
                    parsePack(
                        raw = raw,
                        id = "custom:${file.nameWithoutExtension}",
                        builtIn = false,
                        fileName = file.name,
                    )
                }.getOrNull()
            }
    }

    /** Resolves the currently active [LanguagePack] (following device locale when `SYSTEM_ID` is chosen). */
    fun resolveActive(
        id: String = selectedId,
        builtIns: List<LanguagePack> = builtInPacks,
        customs: List<LanguagePack> = customPacks,
    ): LanguagePack {
        val all = builtIns + customs
        if (id != SYSTEM_ID) {
            all.firstOrNull { it.id == id }?.let { return it }
        }
        val sysLang = Locale.getDefault().language.lowercase(Locale.ROOT).let {
            when (it) {
                "iw" -> "he"
                "in" -> "id"
                "ji" -> "yi"
                else -> it
            }
        }
        val sysTag = Locale.getDefault().toLanguageTag().lowercase(Locale.ROOT)
        return customs.firstOrNull { it.code.lowercase(Locale.ROOT) == sysTag }
            ?: builtIns.firstOrNull { it.code.lowercase(Locale.ROOT) == sysTag }
            ?: customs.firstOrNull { it.code.lowercase(Locale.ROOT) == sysLang }
            ?: builtIns.firstOrNull { it.code.lowercase(Locale.ROOT) == sysLang }
            ?: builtIns.firstOrNull { it.code == "en" }
            ?: EmptyLanguagePack
    }

    /** Non-composable lookup for Toast/status strings produced inside callbacks or coroutines. */
    fun translate(key: String, vararg args: Any): String {
        val pack = resolveActive()
        val template = pack.strings[key] ?: key
        if (args.isEmpty()) return template
        return runCatching { String.format(Locale.getDefault(), template, *args) }
            .getOrElse { runCatching { String.format(Locale.US, key, *args) }.getOrDefault(template) }
    }

    fun select(context: Context, id: String) {
        ensureLoaded(context)
        selectedId = id
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, id)
            .apply()
    }

    fun importPack(context: Context, rawJson: String, fallbackLabel: String? = null): Result<LanguagePack> = runCatching {
        ensureLoaded(context)
        val app = context.applicationContext
        val fallbackSlug = fallbackLabel
            ?.removeSuffix(".json")
            ?.replace(Regex("[^A-Za-z0-9_-]+"), "-")
            ?.trim('-')
            ?.takeIf { it.isNotEmpty() }
            ?: "pack"
        val parsed = parsePack(rawJson, id = "custom:$fallbackSlug", builtIn = false, fallbackCode = fallbackSlug)
        require(parsed.strings.isNotEmpty()) { "No translation strings found in JSON" }
        val slug = (parsed.code.ifBlank { fallbackSlug } + "-" + parsed.name.ifBlank { fallbackSlug })
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "custom-pack" }
        val dir = File(app.filesDir, CUSTOM_DIR).apply { mkdirs() }
        val outFile = File(dir, "$slug.json")
        outFile.writeText(serializePack(parsed), Charsets.UTF_8)
        reloadCustomPacks(app)
        val installed = customPacks.firstOrNull { it.fileName == outFile.name }
            ?: parsed.copy(id = "custom:$slug", fileName = outFile.name)
        select(app, installed.id)
        installed
    }

    fun removeCustomPack(context: Context, pack: LanguagePack) {
        if (pack.builtIn) return
        val app = context.applicationContext
        pack.fileName?.let { File(File(app.filesDir, CUSTOM_DIR), it).delete() }
        reloadCustomPacks(app)
        if (selectedId == pack.id) {
            select(app, SYSTEM_ID)
        }
    }

    fun templateJson(context: Context): String {
        ensureLoaded(context)
        return runCatching {
            context.applicationContext.assets.open("lang/en.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse {
            serializePack(resolveActive(id = "builtin:en"))
        }
    }

    private fun parsePack(
        raw: String,
        id: String,
        builtIn: Boolean,
        fileName: String? = null,
        fallbackCode: String = "custom",
    ): LanguagePack {
        val root = JSONObject(raw.trimStart('\uFEFF'))
        val code = root.optString("code", "").trim().ifEmpty { fallbackCode }
        val baseLang = code.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        val name = root.optString("name", "").trim().ifEmpty { code }
        val nativeName = root.optString("nativeName", "").trim().ifEmpty { name }
        val rtl = if (root.has("rtl")) root.optBoolean("rtl", false) else baseLang in RTL_CODES
        val map = LinkedHashMap<String, String>()
        val nested = root.optJSONObject("strings")
        if (nested != null) {
            val keys = nested.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = nested.optString(k, "")
                if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
            }
        } else {
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k in META_KEYS) continue
                val v = root.opt(k)
                if (v is String && k.isNotEmpty() && v.isNotEmpty()) map[k] = v
            }
        }
        return LanguagePack(
            id = id,
            code = code,
            name = name,
            nativeName = nativeName,
            rtl = rtl,
            builtIn = builtIn,
            strings = map,
            fileName = fileName,
        )
    }

    private fun serializePack(pack: LanguagePack): String {
        val out = JSONObject()
        out.put("code", pack.code)
        out.put("name", pack.name)
        out.put("nativeName", pack.nativeName)
        out.put("rtl", pack.rtl)
        val strings = JSONObject()
        for ((k, v) in pack.strings) strings.put(k, v)
        out.put("strings", strings)
        return out.toString(2) + "\n"
    }
}
