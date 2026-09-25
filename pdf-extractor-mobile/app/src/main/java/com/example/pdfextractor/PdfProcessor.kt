package com.example.pdfextractor

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfProcessor(private val context: Context) {
    init { PDFBoxResourceLoader.init(context) }

    suspend fun process(
        items: List<PdfItem>,
        language: String,
        mode: String,
        quality: String,
        onProgress: (Float, String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val all = StringBuilder()
        val total = items.size.coerceAtLeast(1)
        items.forEachIndexed { i, item ->
            context.contentResolver.openInputStream(item.uri).use { input ->
                if (input == null) return@use
                val doc = PDDocument.load(input)
                try {
                    val text = if (mode != "OCR only") PDFTextStripper().getText(doc).trim() else ""
                    all.append("\n\n===== ${item.name} =====\n\n")
                    if (text.isNotBlank()) all.append(text) else all.append("[OCR required for this PDF/page]")
                } finally { doc.close() }
            }
            onProgress((i + 1).toFloat() / total, item.name)
        }
        all.toString().trim()
    }
}
