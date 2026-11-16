package com.wordlookup.screen.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

data class DictEntry(
    val word: String,
    val phonetic: String,
    val meaning: String,
    val baseForm: String? = null,
    val posHint: String? = null,
    val category: String = "考研高频"
)

object LocalDictionary {
    @Volatile
    private var externalDb: SQLiteDatabase? = null

    private val assetVocabMap = mutableMapOf<String, DictEntry>()

    fun getOfflineDictStatus(): String {
        val db = externalDb
        if (db != null && db.isOpen) {
            runCatching {
                db.rawQuery("SELECT COUNT(*) FROM stardict", null).use { c ->
                    if (c.moveToFirst()) {
                        return "已安装离线 SQLite 词典 (${c.getInt(0)} 词 + 词根推导引擎)"
                    }
                }
            }
        }
        return "已启用内置离线词库 (${assetVocabMap.size} 词 + 词根推导引擎)"
    }

    fun importExternalDbFromUri(context: Context, uri: android.net.Uri): Boolean {
        return runCatching {
            val target = File(context.filesDir, "ecdict.db")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            externalDb?.close()
            externalDb = SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            true
        }.getOrDefault(false)
    }

    fun initFromAssetsIfPresent(context: Context) {
        if (assetVocabMap.isEmpty()) {
            runCatching {
                context.assets.open("core_kaoyan_vocab.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split("\t")
                        if (parts.size >= 3) {
                            val w = parts[0].trim()
                            assetVocabMap[w.lowercase()] = DictEntry(w, parts[1].trim(), parts[2].trim())
                        }
                    }
                }
            }
        }
        if (externalDb != null) return
        runCatching {
            val assetsList = context.assets.list("") ?: return
            if (!assetsList.contains("ecdict.db")) return
            val target = File(context.filesDir, "ecdict.db")
            if (!target.exists()) {
                context.assets.open("ecdict.db").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            externalDb = SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
        }
    }

    private val entries: Map<String, DictEntry> = listOf(
        DictEntry("accessibility", "/əkˌsesəˈbɪləti/", "n. 无障碍；可及性"),
        DictEntry("algorithm", "/ˈælɡərɪðəm/", "n. 算法；计算程序"),
        DictEntry("ambiguous", "/æmˈbɪɡjuəs/", "adj. 模棱两可的；含糊不清的"),
        DictEntry("architecture", "/ˈɑːrkɪtektʃər/", "n. 架构；体系结构"),
        DictEntry("benchmark", "/ˈbentʃmɑːrk/", "n. 基准；标准"),
        DictEntry("build", "/bɪld/", "v. 构建；建造"),
        DictEntry("child", "/tʃaɪld/", "n. 孩子；子节点"),
        DictEntry("collaborate", "/kəˈlæbəreɪt/", "v. 合作；协作"),
        DictEntry("context", "/ˈkɑːntekst/", "n. 上下文；语境；背景"),
        DictEntry("design", "/dɪˈzaɪn/", "v. 设计；构思  n. 设计"),
        DictEntry("device", "/dɪˈvaɪs/", "n. 设备；装置"),
        DictEntry("dictionary", "/ˈdɪkʃəneri/", "n. 词典；字典"),
        DictEntry("efficient", "/ɪˈfɪʃnt/", "adj. 高效的"),
        DictEntry("feature", "/ˈfiːtʃər/", "n. 功能；特征"),
        DictEntry("find", "/faɪnd/", "v. 找到；发现"),
        DictEntry("floating", "/ˈfloʊtɪŋ/", "adj. 浮动的；悬浮的"),
        DictEntry("gesture", "/ˈdʒestʃər/", "n. 手势；姿态"),
        DictEntry("go", "/ɡoʊ/", "v. 去；走；进行"),
        DictEntry("good", "/ɡʊd/", "adj. 好的；优秀的"),
        DictEntry("interface", "/ˈɪntərfeɪs/", "n. 界面；接口"),
        DictEntry("latency", "/ˈleɪtnsi/", "n. 延迟；潜伏期"),
        DictEntry("make", "/meɪk/", "v. 制作；使"),
        DictEntry("overlay", "/ˌoʊvərˈleɪ/", "n. 悬浮层；覆盖层  v. 覆盖"),
        DictEntry("permission", "/pərˈmɪʃn/", "n. 权限；许可"),
        DictEntry("preserve", "/prɪˈzɜːrv/", "v. 保护；保留"),
        DictEntry("process", "/ˈprɑːses/", "v. 处理；加工  n. 过程"),
        DictEntry("prototype", "/ˈproʊtətaɪp/", "n. 原型；雏形"),
        DictEntry("recognition", "/ˌrekəɡˈnɪʃn/", "n. 识别；承认"),
        DictEntry("responsive", "/rɪˈspɑːnsɪv/", "adj. 响应灵敏的"),
        DictEntry("screen", "/skriːn/", "n. 屏幕；银幕  v. 筛查"),
        DictEntry("see", "/siː/", "v. 看见；理解"),
        DictEntry("take", "/teɪk/", "v. 拿；取；花费"),
        DictEntry("translate", "/trænzˈleɪt/", "v. 翻译；转换"),
        DictEntry("vocabulary", "/vəˈkæbjəleri/", "n. 词汇；生词表"),
        DictEntry("write", "/raɪt/", "v. 写；编写"),
        DictEntry("instead of", "/ɪnˈsted əv/", "短语：代替；而不是"),
        DictEntry("in terms of", "/ɪn tɜːrmz əv/", "短语：就……而言；在……方面")
    ).associateBy { it.word.lowercase() }

    private val irregularLemmas = mapOf(
        "went" to "go", "gone" to "go",
        "better" to "good", "best" to "good",
        "took" to "take", "taken" to "take",
        "saw" to "see", "seen" to "see",
        "wrote" to "write", "written" to "write",
        "found" to "find", "made" to "make", "built" to "build",
        "children" to "child"
    )

    private fun querySingleExact(wordLower: String): DictEntry? {
        externalDb?.let { db ->
            runCatching {
                db.rawQuery(
                    "SELECT word, phonetic, translation FROM stardict WHERE lower(word) = ? LIMIT 1",
                    arrayOf(wordLower)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val w = cursor.getString(0) ?: wordLower
                        val ph = cursor.getString(1)?.takeIf { it.isNotBlank() }?.let { "/$it/" } ?: ""
                        val tr = cursor.getString(2) ?: ""
                        if (tr.isNotBlank()) return DictEntry(w, ph, tr)
                    }
                }
            }
        }
        return assetVocabMap[wordLower] ?: entries[wordLower]
    }

    private val prefixMap = listOf(
        "cardio" to "心/心血管", "neuro" to "神经", "myo" to "肌/肌肉", "pulmo" to "肺",
        "hemo" to "血", "vaso" to "血管", "thermo" to "温度", "baro" to "压力",
        "proprio" to "本体感觉", "patho" to "病理", "physio" to "生理",
        "hyper" to "高/过度", "hypo" to "低/不足", "inter" to "在…之间", "intra" to "在…内",
        "trans" to "跨/穿", "sub" to "亚/下", "anti" to "抗/反", "auto" to "自主",
        "de" to "去/除", "dis" to "分离/否定", "endo" to "内", "exo" to "外",
        "homo" to "同质", "iso" to "等", "macro" to "大/宏观", "micro" to "微",
        "multi" to "多", "para" to "副/旁", "peri" to "外周", "post" to "后",
        "pre" to "前/预", "pro" to "促进/前", "re" to "再/复", "syn" to "同步/合", "un" to "不/未"
    )

    private val suffixMap = listOf(
        "ogenesis" to "n. 生物合成/生成", "ization" to "n. …化过程", "ability" to "n. …能力/性质",
        "logical" to "adj. …学的", "ology" to "n. …学", "trophy" to "n. 营养/肥大/萎缩",
        "metric" to "adj. 等…的/测量的", "ation" to "n. …过程/状态", "ment" to "n. …状态/结果",
        "ness" to "n. …性质", "ance" to "n. …状态/量", "ence" to "n. …性质",
        "ity" to "n. …程度/性质", "ism" to "n. …代谢/机制", "ous" to "adj. 具…特征的",
        "ive" to "adj. 具…作用的", "al" to "adj. …相关的", "ic" to "adj. …的"
    )

    private fun analyzeMorphology(word: String): DictEntry? {
        val lower = word.lowercase()
        if (lower.length < 5) return null
        val preHit = prefixMap.firstOrNull { (p, _) -> lower.startsWith(p) && lower.length - p.length >= 3 }
        val sufHit = suffixMap.firstOrNull { (sf, _) -> lower.endsWith(sf) && lower.length - sf.length >= 3 }
        if (preHit == null && sufHit == null) return null
        val parts = mutableListOf<String>()
        preHit?.let { parts.add("前缀 ${it.first}-(${it.second})") }
        sufHit?.let { parts.add("后缀 -${it.first}(${it.second})") }
        return DictEntry(
            word = word,
            phonetic = "离线词根解析",
            meaning = "【构词推导】" + parts.joinToString(" + "),
            category = inferCategory(word, parts.joinToString(""))
        )
    }

    private fun cleanToken(raw: String): String {
        return raw.trim().replace(Regex("^[^a-zA-Z]+|[^a-zA-Z]+$"), "")
    }

    private fun lemmatize(wordLower: String): Pair<String?, DictEntry?> {
        querySingleExact(wordLower)?.let { return null to it }
        irregularLemmas[wordLower]?.let { base ->
            querySingleExact(base)?.let { return base to it }
        }
        val candidates = mutableListOf<String>()
        if (wordLower.endsWith("ies") && wordLower.length > 4) candidates.add(wordLower.dropLast(3) + "y")
        if (wordLower.endsWith("es") && wordLower.length > 3) candidates.add(wordLower.dropLast(2))
        if (wordLower.endsWith("s") && wordLower.length > 2) candidates.add(wordLower.dropLast(1))
        if (wordLower.endsWith("ied") && wordLower.length > 4) candidates.add(wordLower.dropLast(3) + "y")
        if (wordLower.endsWith("ed") && wordLower.length > 3) {
            candidates.add(wordLower.dropLast(2))
            candidates.add(wordLower.dropLast(1))
        }
        if (wordLower.endsWith("ing") && wordLower.length > 4) {
            val stem = wordLower.dropLast(3)
            candidates.add(stem)
            candidates.add(stem + "e")
        }
        for (cand in candidates) {
            querySingleExact(cand)?.let { return cand to it }
        }
        return null to null
    }

    fun inferCategory(word: String, meaning: String): String {
        if (Regex("(肌|神经|心|肺|氧|血|代谢|反射|生理|康复|收缩|电位)").containsMatchIn(meaning)) {
            return "运动生理"
        }
        return if (word.length >= 6) "考研高频" else "日常阅读"
    }

    fun analyzeSentenceClauses(sentence: String): String {
        if (sentence.isBlank()) return ""
        val rules = listOf(
            Regex("\\b(although|though|while|whereas|despite)\\b", RegexOption.IGNORE_CASE) to "让步/对比从句",
            Regex("\\b(because|since|as)\\b", RegexOption.IGNORE_CASE) to "原因/方式从句",
            Regex("\\b(so that|in order that)\\b", RegexOption.IGNORE_CASE) to "目的/结果状语从句",
            Regex("\\b(which|that|who|whom|whose|where)\\b", RegexOption.IGNORE_CASE) to "定语/名词性从句",
            Regex("\\b(when|during|before|after|until)\\b", RegexOption.IGNORE_CASE) to "时间结构",
            Regex("\\b(if|unless)\\b", RegexOption.IGNORE_CASE) to "条件状语从句"
        )
        val matched = rules.mapNotNull { (rx, label) ->
            rx.find(sentence)?.let { "[${it.value}] $label" }
        }
        val parts = sentence.split(Regex(",\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val clauseSplit = if (parts.size > 1) {
            "\n层次切分：" + parts.mapIndexed { i, p -> "(分句${i + 1}) $p" }.joinToString(" / ")
        } else ""
        val summary = if (matched.isNotEmpty()) matched.joinToString("；") else "简单主谓宾主干结构"
        return "语法骨架：$summary$clauseSplit"
    }

    private fun inferPosHint(word: String, sentence: String, meaning: String): String? {
        if (sentence.isBlank() || !meaning.contains("v.") || !meaning.contains("n.")) return null
        val match = Regex("\\b(\\w+)\\s+${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
            .find(sentence) ?: return null
        val prev = match.groupValues[1].lowercase()
        return when (prev) {
            "to", "can", "could", "will", "would", "may", "might", "must", "should" ->
                "语境词性提示：此处更偏向 动词 (v.)"
            "a", "an", "the", "this", "that", "its", "their" ->
                "语境词性提示：此处更偏向 名词 (n.)"
            else -> null
        }
    }

    fun searchBuiltInDictionary(query: String, limit: Int = 5): List<DictEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val results = mutableListOf<DictEntry>()
        for ((k, v) in assetVocabMap) {
            if (k.contains(q) || v.meaning.lowercase().contains(q)) {
                results.add(v.copy(category = inferCategory(v.word, v.meaning)))
                if (results.size >= limit) return results
            }
        }
        for ((k, v) in entries) {
            if (results.none { it.word.equals(v.word, ignoreCase = true) } &&
                (k.contains(q) || v.meaning.lowercase().contains(q))
            ) {
                results.add(v.copy(category = inferCategory(v.word, v.meaning)))
                if (results.size >= limit) return results
            }
        }
        return results
    }

    fun lookup(rawInput: String, sentence: String = ""): DictEntry {
        val trimmed = rawInput.trim()
        val lower = trimmed.lowercase()
        querySingleExact(lower)?.let {
            return it.copy(
                word = trimmed,
                posHint = inferPosHint(trimmed, sentence, it.meaning),
                category = inferCategory(trimmed, it.meaning)
            )
        }

        val tokens = trimmed.split(Regex("\\s+")).map(::cleanToken).filter { it.isNotEmpty() }
        if (tokens.size <= 1) {
            val word = tokens.firstOrNull() ?: trimmed
            val (base, entry) = lemmatize(word.lowercase())
            return if (entry != null) {
                entry.copy(
                    word = word,
                    baseForm = base,
                    posHint = inferPosHint(word, sentence, entry.meaning),
                    category = inferCategory(word, entry.meaning)
                )
            } else {
                analyzeMorphology(word) ?: DictEntry(
                    word = word,
                    phonetic = "",
                    meaning = "当前离线词库暂未收录（可在首页点击「导入外部 .db 大词典」扩容至 77 万词）"
                )
            }
        }

        val combined = tokens.joinToString(" + ") { token ->
            val (_, entry) = lemmatize(token.lowercase())
            val shortMeaning = entry?.meaning?.substringBefore("；")?.trim() ?: "未收录"
            "$token($shortMeaning)"
        }
        return DictEntry(
            word = tokens.joinToString(" "),
            phonetic = "词组连选",
            meaning = combined
        )
    }
}
r