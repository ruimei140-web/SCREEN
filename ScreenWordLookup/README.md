# ScreenWordLookup（屏译词典）

一个基于 **Kotlin + Jetpack Compose + AccessibilityService + ML Kit OCR + Room + PdfDocument + AppWidget** 的安卓原生屏幕长按取词 App。

## 已集成核心功能与学习效率模块
1. **屏幕边缘固定悬浮条**：支持固定在屏幕左/右侧、上下拖动、**3 秒闲置半透明缩边**、全面屏 `systemGestureExclusionRects` 防误触返回，以及 `onConfigurationChanged` **横竖屏自适应**。
2. **长按取词 + 滑动连选 + 同屏生词自动标色 + 顶部快捷栏**：进入取词模式后，顶部悬浮快捷栏支持一键开启**全屏段落双语对照浮层**和**当前屏幕难词预习词单**；屏幕中已收藏的生词自动标出浅橙色虚线框；长按或左右滑动连选时实时绘制蓝色选区框。
3. **词形自动还原 + 语境词性提示 + 整句翻译**：自动还原规则与不规则变形词，结合前文词性提示多义词倾向，支持悬浮卡内一键「译整句」与 `TextToSpeech` 离线发音。
4. **高频盲区 + 闪卡自测 + 桌面小组件 + 多格式导出**：支持重复查词计数、原句挖空闪卡自测、桌面「今日盲区」3 词轮播小组件，以及一键复制、Anki `.tsv` 导出和 `StaticLayout` 自动折行 PDF 导出。

## 如何接入完整离线 ECDICT SQLite 词库
1. 下载开源 `ecdict.db` 并放入 `app/src/main/assets/ecdict.db`。
2. 重新编译 APK，`LocalDictionary.kt` 会自动检测并挂载离线查询（未放入时自动使用内置演示词库）。

## 如何生成 APK 安装包
1. 解压后用 **Android Studio** 打开 `ScreenWordLookup` 文件夹，等待 Gradle 同步完成。
2. 点击顶部菜单 **Build -> Build Bundle(s) / APK(s) -> Build APK(s)** 即可生成 `app-debug.apk`。
r