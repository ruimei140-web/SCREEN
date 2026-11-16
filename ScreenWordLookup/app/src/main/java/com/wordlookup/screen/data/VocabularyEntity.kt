package com.wordlookup.screen.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary")
data class VocabularyEntity(
    @PrimaryKey
    val word: String,
    val phonetic: String,
    val meaning: String,
    val sentence: String = "",
    val category: String = "考研高频", // "运动生理" | "考研高频" | "日常阅读"
    val note: String = "",
    val lookupCount: Int = 1,
    val mastery: String = "new",
    val createdAt: Long = System.currentTimeMillis()
)
r