package com.alastor.ctmforge.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Insets for app bars: none for hidden system bars in immersive mode, cutouts still respected. */
@Composable
fun barInsets(immersive: Boolean): WindowInsets =
    if (immersive) WindowInsets.displayCutout else WindowInsets.systemBars.union(WindowInsets.displayCutout)

@Composable
fun topInsets(immersive: Boolean) = barInsets(immersive).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

@Composable
fun bottomInsets(immersive: Boolean) = barInsets(immersive).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** A tinted card with an icon, a title and text, for hints and warnings. */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (actions != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        content = actions,
                    )
                }
            }
        }
    }
}

/** Pack icon with a fallback glyph. */
@Composable
fun PackIcon(image: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.24f)
    if (image != null) {
        Image(
            image, contentDescription = null, contentScale = ContentScale.Crop,
            filterQuality = if (image.width <= 64) FilterQuality.None else FilterQuality.Low,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.GridView, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
fun ProgressBlock(title: String, subtitle: String, fraction: Float?, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Save to files" through the system file picker. */
@Composable
fun SaveFileButton(file: File, label: String = "Save", modifier: Modifier = Modifier, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { copyToUri(context, file, uri) }
            onResult(if (ok) "Saved ${file.name}" else "Couldn't save the file")
        }
    }
    OutlinedButton(onClick = { launcher.launch(file.name) }, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

fun copyToUri(context: Context, file: File, uri: android.net.Uri): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
    true
}.getOrDefault(false)

val ScreenPadding = PaddingValues(horizontal = 16.dp)
