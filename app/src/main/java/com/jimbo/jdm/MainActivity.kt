package com.jimbo.jdm

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF1B5E20)
private val Leaf = Color(0xFF66BB6A)
private val Mist = Color(0xFF4F6F52)

fun findUrl(t: String?) = t?.let { Regex("https?://\\S+").find(it)?.value }

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<String?>(null)
    private val clip = mutableStateOf<String?>(null)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        handle(intent)
        enableEdgeToEdge()
        setContent { JdmApp(shared, clip) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) shared.value = i.getStringExtra(Intent.EXTRA_TEXT)
    }

    // Android 10+ only lets the focused app read the clipboard
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.value = findUrl(cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString())
        }
    }
}

@Composable
fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val s = RoundedCornerShape(26.dp)
    Column(
        modifier
            .clip(s)
            .background(Brush.linearGradient(listOf(Color.White.copy(.70f), Color.White.copy(.28f))))
            .border(1.dp, Brush.linearGradient(listOf(Color.White, Color.White.copy(.2f))), s)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun JdmApp(shared: MutableState<String?>, clip: MutableState<String?>) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }

    LaunchedEffect(shared.value) {
        findUrl(shared.value)?.let { Store.add(ctx, it) }
        shared.value = null
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Leaf)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF1F8E9), Color(0xFFD7EBD8), Color(0xFFBFE0C3))))) {
                Box(Modifier.offset(180.dp, (-40).dp).size(240.dp).blur(70.dp).background(Color(0xFF81C784).copy(.55f), CircleShape))
                Box(Modifier.offset((-60).dp, 420.dp).size(260.dp).blur(80.dp).background(Color(0xFFDCE775).copy(.45f), CircleShape))
                Box(Modifier.offset(120.dp, 640.dp).size(220.dp).blur(70.dp).background(Color(0xFF4DB6AC).copy(.35f), CircleShape))

                Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(14.dp))
                    Text("Jimbo Download Manager", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("لینک تلگرام، اینستاگرام، توییتر یا لینک مستقیم را بچسبان", fontSize = 13.sp, color = Mist)
                    Spacer(Modifier.height(14.dp))

                    Glass(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("https://…") },
                            shape = RoundedCornerShape(18.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White.copy(.5f), unfocusedContainerColor = Color.White.copy(.35f),
                                focusedBorderColor = Leaf, unfocusedBorderColor = Color.White
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { findUrl(input)?.let { Store.add(ctx, it); input = "" } },
                                enabled = findUrl(input) != null,
                                colors = ButtonDefaults.buttonColors(containerColor = Leaf)
                            ) { Text("دانلود") }
                            OutlinedButton(onClick = { clip.value?.let { input = it } }, enabled = clip.value != null) { Text("چسباندن", color = Ink) }
                        }
                    }

                    clip.value?.let { link ->
                        Spacer(Modifier.height(12.dp))
                        Glass(Modifier.fillMaxWidth()) {
                            Text("لینک کپی‌شده", fontSize = 12.sp, color = Mist)
                            Text(link, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = { Store.add(ctx, link); clip.value = null },
                                colors = ButtonDefaults.buttonColors(containerColor = Ink)
                            ) { Text("دانلود همین لینک") }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    if (Store.items.isEmpty()) {
                        Text("هنوز دانلودی نداری. فایل‌ها در Download/JDM ذخیره می‌شوند.", color = Mist, fontSize = 13.sp)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        items(Store.items, key = { it.id }) { d -> DlCard(d) }
                    }
                }
            }
        }
    }
}

private fun mb(x: Long) = "%.1f".format(x / 1048576f)

@Composable
private fun DlCard(d: Dl) {
    val ctx = LocalContext.current.applicationContext
    val busy = d.status == "resolving" || d.status == "downloading"
    Glass(Modifier.fillMaxWidth()) {
        Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        if (d.status == "resolving" || (d.status == "downloading" && d.progress <= 0f)) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Leaf, trackColor = Color.White.copy(.6f))
        } else {
            LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth(), color = Leaf, trackColor = Color.White.copy(.6f))
        }
        val pct = (d.progress * 100).toInt()
        val line = when (d.status) {
            "resolving" -> "در حال تحلیل لینک…"
            "downloading" -> (if (d.total > 0) "$pct٪ • ${mb(d.bytes)} از ${mb(d.total)} MB" else "${mb(d.bytes)} MB") +
                " • ${"%.1f".format(d.speed / 1048576f)} MB/s"
            "paused" -> "متوقف شد • $pct٪ ذخیره شده"
            "done" -> "ذخیره شد: Download/JDM • ${mb(d.bytes)} MB"
            else -> d.msg + if (d.progress > 0f) " • $pct٪ ذخیره شده" else ""
        }
        Spacer(Modifier.height(6.dp))
        Text(line, fontSize = 12.sp, color = if (d.status == "error") Color(0xFFB3261E) else Mist)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) TextButton({ Store.pause(d) }) { Text("توقف", color = Ink) }
            if (d.status == "paused" || d.status == "error") TextButton({ Store.start(ctx, d) }) { Text("ادامه", color = Ink) }
            if (d.status == "done" && d.uri.isNotEmpty()) TextButton({
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(d.uri), d.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("باز کردن", color = Ink) }
            if (!busy) TextButton({ Store.remove(ctx, d) }) { Text("حذف از لیست", color = Mist) }
        }
    }
}
