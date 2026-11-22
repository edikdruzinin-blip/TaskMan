package com.taskman.root.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.taskman.root.R

/*
 * Building blocks of the Windows 11 look: the content layer, the search box with the accent underline,
 * the WinUI toggle switch, standard / accent buttons, the ContentDialog, the heat-map pill and pivot tabs.
 * Everything else in the app is composed from these and from the Material components restyled by Theme.kt.
 */

// ───────────────────────── layout helpers ─────────────────────────

/** Keeps a top bar below the status bar and away from side cut-outs. */
@Composable
fun Modifier.topBarInsets(): Modifier =
    windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))

/** Keeps a bottom bar above the navigation bar and away from side cut-outs. */
@Composable
fun Modifier.bottomBarInsets(): Modifier =
    windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))

/**
 * The Windows 11 "content layer": a lighter, rounded sheet with a hairline border that lies on the Mica
 * background. Every page of the app is drawn on one.
 */
@Composable
fun ContentLayer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

// ───────────────────────── brand and status ─────────────────────────

/** The app logo: a window with a pulse line on a blue tile. */
@Composable
fun AppLogo(size: Dp = 28.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Color(0xFF0B5CAD)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(size * 0.8f),
            tint = Color.White
        )
    }
}

/** Colored dot and a short text: root granted / denied / being checked. */
@Composable
fun RootStatus(root: RootState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val dot = when (root) {
        RootState.Checking -> Palette.warn
        is RootState.Granted -> Palette.good
        RootState.Denied -> cs.error
    }
    val text = when (root) {
        RootState.Checking -> stringResource(R.string.root_checking_short)
        is RootState.Granted -> stringResource(R.string.root_active, root.manager)
        RootState.Denied -> stringResource(R.string.root_none)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ───────────────────────── inputs ─────────────────────────

/**
 * Windows 11 search box: a raised field with a hairline border; while it has focus an accent line
 * appears along its bottom edge.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            try {
                focus.requestFocus()
            } catch (e: Exception) {
                // the field is not attached yet; the user can tap it
            }
        }
    }
    Box(
        modifier
            .clip(shape)
            .background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outlineVariant, shape)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fl_search),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = cs.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }
                )
            }
            if (value.isNotEmpty()) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_fl_close),
                        contentDescription = stringResource(R.string.close),
                        modifier = Modifier.size(14.dp),
                        tint = cs.onSurfaceVariant
                    )
                }
            }
        }
        if (focused) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(cs.primary)
            )
        }
    }
}

/** WinUI toggle switch: an outlined pill with a small knob; filled with the accent color when on. */
@Composable
fun WinToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    val knobX by animateDpAsState(if (checked) 24.dp else 4.dp, tween(120), label = "toggleKnob")
    val trackShape = RoundedCornerShape(10.dp)
    val track = if (checked) cs.primary else Color.Transparent
    val stroke = if (checked) cs.primary else cs.onSurfaceVariant
    val knob = if (checked) cs.onPrimary else cs.onSurfaceVariant
    Box(
        modifier
            .size(width = 52.dp, height = 44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 20.dp)
                .clip(trackShape)
                .background(track)
                .border(1.dp, stroke, trackShape)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = knobX)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(knob)
            )
        }
    }
}

/** A toggle with the "On" / "Off" caption in front of it, like the Windows 11 startup apps list. */
@Composable
fun ToggleWithLabel(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (checked) R.string.toggle_on else R.string.toggle_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        WinToggle(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

enum class WinButtonStyle { Standard, Accent, Danger }

/** Rectangular 4 dp button: standard (raised, outlined), accent (filled) or danger. */
@Composable
fun WinButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: WinButtonStyle = WinButtonStyle.Standard,
    enabled: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    val fill = when (style) {
        WinButtonStyle.Accent -> cs.primary
        WinButtonStyle.Danger -> cs.error
        WinButtonStyle.Standard -> cs.surfaceContainerLowest
    }
    val content = when (style) {
        WinButtonStyle.Accent -> cs.onPrimary
        WinButtonStyle.Danger -> cs.onError
        WinButtonStyle.Standard -> cs.onSurface
    }
    val edge = if (style == WinButtonStyle.Standard) cs.outline.copy(alpha = 0.45f) else Color.Transparent
    Box(
        modifier
            .heightIn(min = 40.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A radio-button row for option lists (update speed, theme). */
@Composable
fun ChoiceRow(
    selected: Boolean,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Pivot tabs: plain labels, the selected one is bold with a short accent line below it
 * (the WinUI "selector bar"). Optional trailing action, e.g. a refresh button.
 */
@Composable
fun <T> PivotRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
        ) {
            options.forEach { (value, label) ->
                val sel = value == selected
                Column(
                    Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onSelect(value) }
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) cs.onSurface else cs.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .width(20.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (sel) cs.primary else Color.Transparent)
                    )
                }
            }
        }
        trailing()
    }
}

// ───────────────────────── dialog ─────────────────────────

/**
 * WinUI ContentDialog: a rounded card with a title, the content, and a darker command strip at the
 * bottom holding the buttons (usually [WinButton]s with `Modifier.weight(1f)`).
 */
@Composable
fun FluentDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 300.dp, max = 480.dp),
            shape = RoundedCornerShape(8.dp),
            color = cs.surfaceContainerHigh,
            border = BorderStroke(1.dp, cs.outlineVariant)
        ) {
            Column {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 20.dp)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    content()
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(cs.background)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = buttons
                )
            }
        }
    }
}

// ───────────────────────── data ─────────────────────────

/**
 * A value with its label on a heat-map tinted pill: "CPU 18%", "Memory 1.23 GB".
 * [fraction] is how busy the resource is, 0..1.
 */
@Composable
fun HeatPill(label: String, value: String, fraction: Float, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.55f))
            .background(heatBackground(fraction))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            // on a strongly tinted pill the quiet label color would be too faint
            color = if (fraction >= 0.3f) cs.onSurface else cs.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.width(5.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Expand / collapse arrow of a group row. */
@Composable
fun ExpandChevron(expanded: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Icon(
        painter = painterResource(
            if (expanded) R.drawable.ic_fl_chevron_down else R.drawable.ic_fl_chevron_right
        ),
        contentDescription = null,
        modifier = modifier.size(size),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Small accent chevron that marks the sorted column: down = descending, up = ascending. */
@Composable
fun SortChevron(desc: Boolean, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(
            if (desc) R.drawable.ic_fl_chevron_down else R.drawable.ic_fl_chevron_up
        ),
        contentDescription = null,
        modifier = modifier.size(12.dp),
        tint = MaterialTheme.colorScheme.primary
    )
}
