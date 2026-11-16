package com.wordlookup.screen.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wordlookup.screen.data.AppPreferences
import com.wordlookup.screen.data.DockSide
import com.wordlookup.screen.data.LocalDictionary
import com.wordlookup.screen.data.VocabularyDatabase
import com.wordlookup.screen.data.VocabularyEntity
import com.wordlookup.screen.service.ScreenLookupAccessibilityService
import com.wordlookup.screen.widget.VocabWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalDictionary.initFromAssetsIfPresent(this)
        setContent {
            MaterialTheme {
                ScreenWordLookupApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenWordLookupApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { VocabularyDatabase.getInstance(context).vocabularyDao() }
    val rawVocabList by dao.observeAll().collectAsState(initial = emptyList())

    var serviceEnabled by remember { mutableStateOf(ScreenLookupAccessibilityService.isRunning) }
    var dictStatus by remember { mutableStateOf(LocalDictionary.getOfflineDictStatus()) }
    val dbPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val ok = LocalDictionary.importExternalDbFromUri(context, uri)
                withContext(Dispatchers.Main) {
                    dictStatus = LocalDictionary.getOfflineDictStatus()
                    Toast.makeText(
                        context,
                        if (ok) "已成功安装外部离线大词典！" else "词典文件格式不兼容，请选择包含 stardict 表的 .db 文件",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    var dockSide by remember { mutableStateOf(AppPreferences.getDockSide(context)) }
    var autoExitOnSave by remember { mutableStateOf(AppPreferences.isAutoExitAfterSave(context)) }
    var darkMode by remember { mutableStateOf(AppPreferences.isDarkMode(context)) }
    var sortByFreq by remember { mutableStateOf(true) }
    var allExpanded by remember { mutableStateOf(false) }
    var hideMasteredInQuiz by remember { mutableStateOf(true) }
    var maskSpellingInQuiz by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var quizMode by remember { mutableStateOf(false) }
    var quizIndex by remember { mutableIntStateOf(0) }
    var quizRevealed by remember { mutableStateOf(false) }

    val vocabList = remember(rawVocabList, sortByFreq, selectedCategory, searchQuery) {
        val q = searchQuery.trim().lowercase()
        val filtered = rawVocabList.filter {
            (selectedCategory == "ALL" || it.category == selectedCategory) &&
                (q.isEmpty() || it.word.lowercase().contains(q) || it.meaning.lowercase().contains(q) || it.note.lowercase().contains(q))
        }
        if (sortByFreq) {
            filtered.sortedByDescending {
                (if (it.mastery == "forgot") 100 else 0) + it.lookupCount
            }
        } else {
            filtered
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = ScreenLookupAccessibilityService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val quizPool = remember(vocabList, hideMasteredInQuiz) {
        if (hideMasteredInQuiz) vocabList.filter { it.mastery != "known" } else vocabList
    }

    if (quizMode) {
        FullScreenFlashcardScreen(
            pool = quizPool,
            index = quizIndex,
            revealed = quizRevealed,
            hideMastered = hideMasteredInQuiz,
            maskSpelling = maskSpellingInQuiz,
            onBack = { quizMode = false },
            onToggleReveal = { quizRevealed = !quizRevealed },
            onPrev = {
                if (quizIndex > 0) {
                    quizIndex -= 1
                    quizRevealed = false
                }
            },
            onNext = {
                if (quizPool.isNotEmpty()) {
                    quizIndex = (quizIndex + 1) % quizPool.size
                    quizRevealed = false
                }
            },
            onMarkMastered = { word ->
                scope.launch(Dispatchers.IO) {
                    dao.updateMastery(word, "known")
                    VocabWidgetProvider.refreshNow(context)
                }
                quizRevealed = false
            },
            onToggleStar = { item ->
                val next = if (item.mastery == "forgot") "new" else "forgot"
                scope.launch(Dispatchers.IO) {
                    dao.updateMastery(item.word, next)
                    VocabWidgetProvider.refreshNow(context)
                }
            },
            onToggleHideMastered = {
                hideMasteredInQuiz = !hideMasteredInQuiz
                quizIndex = 0
                quizRevealed = false
            },
            onToggleMaskSpelling = {
                maskSpellingInQuiz = !maskSpellingInQuiz
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("屏译词典 · 屏幕长按取词") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (serviceEnabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (serviceEnabled) "无障碍取词服务已开启" else "需要开启无障碍取词服务",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "开启后支持边缘固定悬浮条、同屏生词自动标色、长按/拖动选词及桌面小组件。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        ) {
                            Text(if (serviceEnabled) "前往系统设置管理" else "立即开启无障碍服务")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "📚 离线大词典引擎",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = dictStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { dbPickerLauncher.launch(arrayOf("*/*")) }
                        ) {
                            Text("从手机存储导入完整版 ecdict.db 大词典")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "悬浮条固定位置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(
                                selected = dockSide == DockSide.LEFT,
                                onClick = {
                                    dockSide = DockSide.LEFT
                                    AppPreferences.setDockSide(context, DockSide.LEFT)
                                    context.sendBroadcast(
                                        Intent(ScreenLookupAccessibilityService.ACTION_UPDATE_DOCK)
                                            .setPackage(context.packageName)
                                    )
                                },
                                label = { Text("固定在屏幕左侧") }
                            )
                            FilterChip(
                                selected = dockSide == DockSide.RIGHT,
                                onClick = {
                                    dockSide = DockSide.RIGHT
                                    AppPreferences.setDockSide(context, DockSide.RIGHT)
                                    context.sendBroadcast(
                                        Intent(ScreenLookupAccessibilityService.ACTION_UPDATE_DOCK)
                                            .setPackage(context.packageName)
                                    )
                                },
                                label = { Text("固定在屏幕右侧") }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(
                                selected = autoExitOnSave,
                                onClick = {
                                    autoExitOnSave = !autoExitOnSave
                                    AppPreferences.setAutoExitAfterSave(context, autoExitOnSave)
                                },
                                label = { Text("收藏后自动退层") }
                            )
                            FilterChip(
                                selected = darkMode,
                                onClick = {
                                    darkMode = !darkMode
                                    AppPreferences.setDarkMode(context, darkMode)
                                },
                                label = { Text("🌙 悬浮窗深色模式") }
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "我的生词本（${vocabList.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyVocabToClipboard(context, vocabList) },
                            enabled = vocabList.isNotEmpty()
                        ) { Text("一键复制") }
                        OutlinedButton(
                            onClick = { exportVocabToAnkiAndShare(context, vocabList) },
                            enabled = vocabList.isNotEmpty()
                        ) { Text("Anki .tsv") }
                        Button(
                            onClick = { exportVocabToPdfAndShare(context, vocabList) },
                            enabled = vocabList.isNotEmpty()
                        ) { Text("导出 PDF") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !quizMode,
                            onClick = { quizMode = false },
                            label = { Text("生词列表") }
                        )
                        FilterChip(
                            selected = quizMode,
                            onClick = {
                                quizMode = true
                                quizRevealed = false
                            },
                            label = { Text("闪卡自测") }
                        )
                        FilterChip(
                            selected = sortByFreq,
                            onClick = { sortByFreq = !sortByFreq },
                            label = { Text(if (sortByFreq) "高频优先" else "最新优先") }
                        )
                        FilterChip(
                            selected = allExpanded,
                            onClick = { allExpanded = !allExpanded },
                            label = { Text(if (allExpanded) "全部折叠" else "全部展开") }
                        )
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("🔍 搜索已存生词，或输入中/英文反查内置考研词库") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ALL" to "全部", "运动生理" to "#运动生理", "考研高频" to "#考研高频", "日常阅读" to "#日常阅读").forEach { (key, label) ->
                            FilterChip(
                                selected = selectedCategory == key,
                                onClick = { selectedCategory = key },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            if (vocabList.isEmpty()) {
                item {
                    Text(
                        text = "暂无生词。在取词悬浮窗点击「加入生词本」后，单词和原句会保存在这里。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            } else {
                if (searchQuery.trim().isNotEmpty()) {
                    val savedWordsLower = rawVocabList.map { it.word.lowercase() }.toSet()
                    val dictHits = LocalDictionary.searchBuiltInDictionary(searchQuery)
                        .filter { it.word.lowercase() !in savedWordsLower }
                    items(dictHits, key = { "dict_${it.word}" }) { hit ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${hit.word} ${hit.phonetic} · #${hit.category}", fontWeight = FontWeight.Bold)
                                    Text(hit.meaning, style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        dao.upsert(
                                            VocabularyEntity(
                                                word = hit.word.lowercase(),
                                                phonetic = hit.phonetic,
                                                meaning = hit.meaning,
                                                category = hit.category
                                            )
                                        )
                                        VocabWidgetProvider.refreshNow(context)
                                    }
                                }) {
                                    Text("＋生词本")
                                }
                            }
                        }
                    }
                }
                items(vocabList, key = { it.word }) { item ->
                    VocabItemCard(
                        item = item,
                        forceExpanded = allExpanded,
                        onUpdateNote = { newNote ->
                            scope.launch(Dispatchers.IO) {
                                dao.updateNote(item.word, newNote)
                            }
                        },
                        onToggleMastery = {
                            val next = if (item.mastery == "known") "forgot" else "known"
                            scope.launch(Dispatchers.IO) {
                                dao.updateMastery(item.word, next)
                                VocabWidgetProvider.refreshNow(context)
                            }
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                dao.delete(item)
                                VocabWidgetProvider.refreshNow(context)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FullScreenFlashcardScreen(
    pool: List<VocabularyEntity>,
    index: Int,
    revealed: Boolean,
    hideMastered: Boolean,
    maskSpelling: Boolean,
    onBack: () -> Unit,
    onToggleReveal: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onMarkMastered: (String) -> Unit,
    onToggleStar: (VocabularyEntity) -> Unit,
    onToggleHideMastered: () -> Unit,
    onToggleMaskSpelling: () -> Unit
) {
    val safeIdx = if (pool.isEmpty()) 0 else index.coerceIn(0, pool.lastIndex)
    val item = pool.getOrNull(safeIdx)

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onBack) {
                        Text("‹ 返回")
                    }
                    Text(
                        text = if (pool.isEmpty()) "0/0" else "${safeIdx + 1}/${pool.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (item != null) {
                        FilterChip(
                            selected = item.mastery == "forgot",
                            onClick = { onToggleStar(item) },
                            label = { Text(if (item.mastery == "forgot") "★" else "☆") }
                        )
                        FilterChip(
                            selected = maskSpelling,
                            onClick = onToggleMaskSpelling,
                            label = { Text("abc") }
                        )
                        FilterChip(
                            selected = item.mastery == "known",
                            onClick = { onMarkMastered(item.word) },
                            label = { Text("熟") }
                        )
                    }
                    FilterChip(
                        selected = hideMastered,
                        onClick = onToggleHideMastered,
                        label = { Text(if (hideMastered) "已藏熟词" else "含熟词") }
                    )
                }
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedButton(
                    onClick = onPrev,
                    enabled = safeIdx > 0,
                    modifier = Modifier.weight(0.35f).height(48.dp)
                ) {
                    Text("←")
                }
                Button(
                    onClick = onNext,
                    enabled = pool.isNotEmpty(),
                    modifier = Modifier.weight(0.65f).height(48.dp)
                ) {
                    Text("→")
                }
            }
        }
    ) { padding ->
        if (item == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("当前没有待测生词（或全部已标为「熟」）。", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onToggleHideMastered) {
                    Text("切换显示已掌握的熟词")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .clickable { onToggleReveal() }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val displayedWord = if (maskSpelling && !revealed && item.word.length > 1) {
                        item.word.first() + "•".repeat(item.word.length - 1)
                    } else {
                        item.word
                    }
                    Text(
                        text = displayedWord,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "美 🔊  ${item.phonetic}   #${item.category}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (revealed) {
                        Text(
                            text = item.meaning,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (item.note.isNotEmpty()) {
                            Text(
                                text = "💡 助记：${item.note}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.65f).height(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {}
                        Card(
                            modifier = Modifier.fillMaxWidth(0.45f).height(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {}
                    }
                    if (item.sentence.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Text(
                                text = item.sentence,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                Text(
                    text = if (revealed) "轻点空白处收起释义 · 点右上角「熟」不再出现" else "点击空白处查看释义",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

private fun copyVocabToClipboard(context: Context, list: List<VocabularyEntity>) {
    if (list.isEmpty()) return
    val text = list.mapIndexed { idx, item ->
        val phonetic = if (item.phonetic.isNotEmpty()) " ${item.phonetic}" else ""
        val sent = if (item.sentence.isNotEmpty()) "\n   例句：${item.sentence}" else ""
        "${idx + 1}. ${item.word}$phonetic — ${item.meaning}$sent"
    }.joinToString("\n")

    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Vocabulary", text))
    Toast.makeText(context, "已复制 ${list.size} 个生词到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun exportVocabToAnkiAndShare(context: Context, list: List<VocabularyEntity>) {
    if (list.isEmpty()) return
    val content = list.joinToString("\n") { item ->
        val back = listOf(
            item.phonetic,
            item.meaning,
            if (item.note.isNotEmpty()) "笔记：${item.note}" else "",
            if (item.sentence.isNotEmpty()) "例句：${item.sentence}" else ""
        ).filter { it.isNotBlank() }.joinToString("<br>")
        "${item.word}\t$back"
    }
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val outFile = File(dir, "vocabulary_anki.tsv")
    outFile.writeText(content, Charsets.UTF_8)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/tab-separated-values"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "导出 Anki 生词卡片 (.tsv)"))
}

private fun exportVocabToPdfAndShare(context: Context, list: List<VocabularyEntity>) {
    if (list.isEmpty()) return
    val pdf = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val marginX = 40f
    val contentWidth = (pageWidth - marginX * 2 - 14).toInt()

    var pageNumber = 1
    var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var canvas = page.canvas

    val titlePaint = Paint().apply {
        color = Color.BLACK
        textSize = 20f
        isFakeBoldText = true
        isAntiAlias = true
    }
    val wordPaint = TextPaint().apply {
        color = Color.parseColor("#1A73E8")
        textSize = 14f
        isFakeBoldText = true
        isAntiAlias = true
    }
    val bodyPaint = TextPaint().apply {
        color = Color.DKGRAY
        textSize = 12f
        isAntiAlias = true
    }
    val sentencePaint = TextPaint().apply {
        color = Color.GRAY
        textSize = 11f
        isAntiAlias = true
    }

    canvas.drawText("屏译词典 · 我的生词本 (${list.size})", marginX, 52f, titlePaint)
    var y = 84f

    for ((idx, item) in list.withIndex()) {
        val headerStr = "${idx + 1}. [#${item.category}] ${item.word} ${item.phonetic}  (查${item.lookupCount}次)"
        val headerLayout = StaticLayout.Builder.obtain(
            headerStr, 0, headerStr.length, wordPaint, contentWidth
        ).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        val meaningLayout = StaticLayout.Builder.obtain(
            item.meaning, 0, item.meaning.length, bodyPaint, contentWidth
        ).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        val sentText = listOfNotNull(
            item.note.takeIf { it.isNotEmpty() }?.let { "笔记：$it" },
            item.sentence.takeIf { it.isNotEmpty() }?.let { "例句：$it" }
        ).joinToString("  |  ")
        val sentLayout = if (sentText.isNotEmpty()) {
            StaticLayout.Builder.obtain(
                sentText, 0, sentText.length, sentencePaint, contentWidth
            ).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        } else null

        val blockHeight = headerLayout.height + meaningLayout.height + (sentLayout?.height ?: 0) + 18f

        if (y + blockHeight > pageHeight - 50) {
            pdf.finishPage(page)
            pageNumber += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = 48f
        }

        canvas.save()
        canvas.translate(marginX, y)
        headerLayout.draw(canvas)
        canvas.translate(12f, headerLayout.height.toFloat() + 2f)
        meaningLayout.draw(canvas)
        if (sentLayout != null) {
            canvas.translate(0f, meaningLayout.height.toFloat() + 2f)
            sentLayout.draw(canvas)
        }
        canvas.restore()

        y += blockHeight
    }
    pdf.finishPage(page)

    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val outFile = File(dir, "vocabulary_export.pdf")
    FileOutputStream(outFile).use { pdf.writeTo(it) }
    pdf.close()

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "导出生词本 PDF"))
}

@Composable
private fun VocabItemCard(
    item: VocabularyEntity,
    forceExpanded: Boolean,
    onUpdateNote: (String) -> Unit,
    onToggleMastery: () -> Unit,
    onDelete: () -> Unit
) {
    var localExpanded by remember(item.word) { mutableStateOf(false) }
    var noteDraft by remember(item.word, item.note) { mutableStateOf(item.note) }
    val isExpanded = forceExpanded || localExpanded

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { localExpanded = !localExpanded }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${item.word} ${item.phonetic}  ·  #${item.category}  ·  查${item.lookupCount}次".trim(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isExpanded) "▲ 收起" else "▼ 点击看释义",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                AnimatedVisibility(visible = isExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.meaning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = noteDraft,
                                onValueChange = {
                                    noteDraft = it
                                    onUpdateNote(it)
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("✏️ 助记笔记") }
                            )
                            OutlinedButton(onClick = onToggleMastery) {
                                Text(if (item.mastery == "known") "✓已掌握" else "标记掌握")
                            }
                        }
                        if (item.sentence.isNotEmpty()) {
                            Text(
                                text = "例句：${item.sentence}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除生词")
            }
        }
    }
}
r