package com.example.pdfextractor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Bg = Color(0xFF050817)
private val Card = Color(0xFF11182B)
private val Field = Color(0xFF18243A)
private val Blue = Color(0xFF2166F3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PdfExtractorApp() }
    }
}

data class PdfItem(val uri: Uri, val name: String)

@Composable
fun PdfExtractorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(listOf<PdfItem>()) }
    var language by remember { mutableStateOf("Urdu + Arabic + English") }
    var mode by remember { mutableStateOf("Auto (Text + OCR)") }
    var quality by remember { mutableStateOf("Balanced") }
    var preview by remember { mutableStateOf("Extracted text will appear here.") }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Choose a PDF to begin") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            items = items + uris.map { PdfItem(it, it.lastPathSegment?.substringAfterLast('/') ?: "document.pdf") }
            message = "${uris.size} PDF${if (uris.size == 1) "" else "s"} added"
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg, surface = Card)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Blue, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                        Text("▤", color = Color.White, fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("PDF Text Extractor", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("Local PDF → UTF-8 TXT • Urdu • Arabic • English", color = Color(0xFF9AA6BF), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Card), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    Modifier.fillMaxWidth().height(135.dp).background(Color(0xFF070B1B), MaterialTheme.shapes.medium).clickable { picker.launch(arrayOf("application/pdf")) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("⇧", color = Color(0xFF57A1FF), fontSize = 30.sp)
                                        Text("Select PDF", color = Color.White, fontSize = 18.sp)
                                        Text("Large PDFs are processed page by page", color = Color(0xFF77839D), fontSize = 12.sp)
                                    }
                                }
                                SettingField("Language", language) {
                                    language = when (language) {
                                        "Urdu + Arabic + English" -> "English"
                                        "English" -> "Arabic"
                                        "Arabic" -> "Urdu"
                                        else -> "Urdu + Arabic + English"
                                    }
                                }
                                SettingField("Mode", mode) {
                                    mode = when (mode) {
                                        "Auto (Text + OCR)" -> "Text only"
                                        "Text only" -> "OCR only"
                                        else -> "Auto (Text + OCR)"
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.weight(1f)) {
                                        SettingField("OCR quality", quality) {
                                            quality = when (quality) {
                                                "Balanced" -> "High"
                                                "High" -> "Fast"
                                                else -> "Balanced"
                                            }
                                        }
                                    }
                                    Box(Modifier.weight(1f)) { SettingField("DPI", "300") {} }
                                }
                                Button(
                                    onClick = {
                                        if (!busy && items.isNotEmpty()) {
                                            busy = true
                                            progress = 0f
                                            scope.launch {
                                                val out = PdfProcessor(context).process(items, language, mode, quality) { p, _ ->
                                                    progress = p
                                                    message = "Processing ${(p * 100).toInt()}%"
                                                }
                                                preview = out
                                                progress = 1f
                                                message = "Completed"
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = items.isNotEmpty() && !busy,
                                    modifier = Modifier.fillMaxWidth().height(50.dp)
                                ) { Text(if (busy) "Processing…" else "Start Extraction", fontWeight = FontWeight.Bold) }
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(message, color = Color(0xFF8793AA), fontSize = 12.sp)
                                    Text("${(progress * 100).toInt()}%", color = Color(0xFF8793AA), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Card)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Text Preview", color = Color.White, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                Box(Modifier.fillMaxWidth().heightIn(min = 280.dp).background(Color(0xFF030617), MaterialTheme.shapes.medium).padding(14.dp)) {
                                    Text(
                                        preview,
                                        color = Color(0xFFE6EAF3),
                                        fontSize = 14.sp,
                                        lineHeight = 21.sp,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = if (preview.any { it in '\u0600'..'\u06FF' }) TextAlign.Right else TextAlign.Left
                                    )
                                }
                            }
                        }
                    }
                    if (items.isNotEmpty()) item {
                        Card(colors = CardDefaults.cardColors(containerColor = Card)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Queue", color = Color.White, fontWeight = FontWeight.Bold)
                                items.forEach { Text("• ${it.name}", color = Color(0xFFADB7CA), modifier = Modifier.padding(top = 7.dp)) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Processing happens locally on your device. No PDF is uploaded to a cloud AI service.", color = Color(0xFF59657C), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SettingField(label: String, value: String, onClick: () -> Unit) {
    Column {
        Text(label, color = Color(0xFFB8C1D3), fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().background(Field, MaterialTheme.shapes.small).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(value, color = Color.White, fontSize = 14.sp)
        }
    }
}
