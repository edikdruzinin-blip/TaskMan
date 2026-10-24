@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskman.root.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taskman.root.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of an icon lookup: not loaded yet, loaded, or the app has no usable icon. */
sealed interface IconState {
    data object Loading : IconState
    data object Missing : IconState
    class Ready(val bitmap: ImageBitmap) : IconState
}

/** Loads and caches launcher icons off the main thread. */
class IconLoader(private val pm: PackageManager) {
    private val cache = LruCache<String, ImageBitmap>(400)
    private val missing = HashSet<String>()

    fun peek(pkg: String): IconState {
        val hit = cache.get(pkg)
        if (hit != null) return IconState.Ready(hit)
        val isMissing = synchronized(missing) { pkg in missing }
        return if (isMissing) IconState.Missing else IconState.Loading
    }

    /** Drops the "no icon" cache, e.g. after packages were installed or updated. */
    fun clearMissing() {
        synchronized(missing) { missing.clear() }
    }

    suspend fun load(pkg: String): IconState {
        val now = peek(pkg)
        if (now !is IconState.Loading) return now
        return withContext(Dispatchers.IO) {
            try {
                val bmp = render(pm.getApplicationIcon(pkg), ICON_PX).asImageBitmap()
                cache.put(pkg, bmp)
                IconState.Ready(bmp)
            } catch (e: Exception) {
                synchronized(missing) { missing.add(pkg) }
                IconState.Missing
            }
        }
    }

    /**
     * Draws the icon into a square bitmap. An AdaptiveIconDrawable already scales its layers and applies
     * the device's icon mask (circle, squircle ...) to whatever bounds it is given, so no special case is needed.
     */
    private fun render(d: Drawable, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, px, px)
        d.draw(canvas)
        return bmp
    }

    private companion object {
        const val ICON_PX = 108
    }
}

val LocalIcons = staticCompositionLocalOf<IconLoader> { error("IconLoader is not provided") }

/**
 * Icon of an app, with fallbacks:
 *  - no package (native process, init service, kernel thread, module) -> gear;
 *  - the package has no usable icon                                     -> Android robot.
 * While the icon is still loading nothing is drawn (no flicker of placeholders).
 */
@Composable
fun AppIcon(pkg: String?, size: Dp = 28.dp, tint: Color? = null) {
    if (pkg == null) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(size),
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        val loader = LocalIcons.current
        val state by produceState<IconState>(initialValue = loader.peek(pkg), key1 = pkg) {
            value = loader.load(pkg)
        }
        when (val st = state) {
            is IconState.Ready -> Image(
                bitmap = st.bitmap,
                contentDescription = null,
                modifier = Modifier.size(size)
            )
            IconState.Missing -> Icon(
                painter = painterResource(R.drawable.ic_android),
                contentDescription = null,
                modifier = Modifier.size(size),
                tint = Color.Unspecified
            )
            IconState.Loading -> Spacer(Modifier.size(size))
        }
    }
}

/**
 * Task-Manager style graph: grid of 10x10 cells, newest sample on the right edge,
 * one or more series drawn as a line with a translucent fill.
 */
@Composable
fun LineGraph(
    series: List<List<Float>>,
    colors: List<Color>,
    maxValue: Float,
    modifier: Modifier = Modifier,
    capacity: Int = MainViewModel.HISTORY
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
    val frame = colors.firstOrNull() ?: MaterialTheme.colorScheme.primary
    Canvas(modifier.background(frame.copy(alpha = 0.06f))) {
        val w = size.width
        val h = size.height
        val hair = 1.dp.toPx()
        for (i in 1..9) {
            val y = h * i / 10f
            drawLine(grid, Offset(0f, y), Offset(w, y), hair)
            val x = w * i / 10f
            drawLine(grid, Offset(x, 0f), Offset(x, h), hair)
        }
        val step = w / (capacity - 1).coerceAtLeast(1)
        series.forEachIndexed { idx, data ->
            if (data.size >= 2 && maxValue > 0f && colors.isNotEmpty()) {
                val color = colors[idx % colors.size]
                val path = Path()
                data.forEachIndexed { i, v ->
                    val x = w - (data.size - 1 - i) * step
                    val y = h - (v / maxValue).coerceIn(0f, 1f) * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val fill = Path()
                fill.addPath(path)
                fill.lineTo(w, h)
                fill.lineTo(w - (data.size - 1) * step, h)
                fill.close()
                drawPath(fill, color.copy(alpha = 0.22f))
                drawPath(path, color, style = Stroke(width = 1.6.dp.toPx()))
            }
        }
        drawRect(frame, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** Table cell with the "heat map" shading the Windows Task Manager uses for busy resources. */
@Composable
fun HeatCell(text: String, fraction: Float, width: Dp, modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val strength = fraction.coerceIn(0f, 1f) * (if (dark) 0.50f else 0.75f)
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(Palette.heat.copy(alpha = strength))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

/** Two-column grid of "label / value" pairs. */
@Composable
fun StatGrid(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier) {
        items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Blue band with a title, used for sections inside lists. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Centered error message with a retry button (sampling / loader failure). */
@Composable
fun ErrorBox(text: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.retry))
        }
    }
}

/** Compact "sort by …" button with a dropdown; used by the phone layouts. */
@Composable
fun SortBar(
    current: SortState,
    keys: List<SortKey>,
    onSort: (SortKey) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Box {
            TextButton(onClick = { open = true }) {
                Text(
                    stringResource(R.string.sort_by) + ": " + sortTitle(current.key) +
                        (if (current.desc) " \u25BC" else " \u25B2")
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                keys.forEach { key ->
                    val mark = if (key == current.key) (if (current.desc) " \u25BC" else " \u25B2") else ""
                    DropdownMenuItem(
                        text = { Text(sortTitle(key) + mark) },
                        onClick = {
                            onSort(key)
                            open = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun sortTitle(key: SortKey): String = stringResource(
    when (key) {
        SortKey.NAME -> R.string.col_name
        SortKey.CPU -> R.string.col_cpu
        SortKey.MEMORY -> R.string.col_memory
        SortKey.DISK -> R.string.col_disk
        SortKey.NETWORK -> R.string.col_network
        SortKey.PID -> R.string.col_pid
        SortKey.STATE -> R.string.col_state
        SortKey.USER -> R.string.col_user
        SortKey.THREADS -> R.string.col_threads
        SortKey.NICE -> R.string.col_nice
        SortKey.OOM -> R.string.col_oom
    }
)

/** One-line error with a retry button above a list that keeps its previous data. */
@Composable
fun ErrorBanner(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.retry))
        }
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Row of filter chips with an optional trailing action (refresh button). */
@Composable
fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(label) }
                )
            }
        }
        trailing()
    }
}
