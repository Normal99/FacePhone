package com.family.grandmaphone.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Samsung One UI's look, built with this app's own components: soft grey or black backgrounds, big
 * rounded cards, large titles and the phone's own font. It ships none of Samsung's assets or code.
 */
private val OneUiLight = lightColorScheme(
    primary = Color(0xFF0381FE),
    onPrimary = Color.White,
    background = Color(0xFFF2F2F2),
    onBackground = Color(0xFF252525),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF252525),
    surfaceVariant = Color(0xFFFCFCFC),
    onSurfaceVariant = Color(0xFF6F6F6F),
    outlineVariant = Color(0xFFE3E3E3),
)

private val OneUiDark = darkColorScheme(
    primary = Color(0xFF3E91FF),
    onPrimary = Color.White,
    background = Color.Black,
    onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF171717),
    onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF171717),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outlineVariant = Color(0xFF2C2C2C),
)

val OneUiCardShape = RoundedCornerShape(26.dp)

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Follows the phone's light or dark setting, and blends the system bars into the background. */
@Composable
fun FacesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) OneUiDark else OneUiLight
    val view = LocalView.current
    SideEffect {
        val window = view.context.activity()?.window ?: return@SideEffect
        window.statusBarColor = colors.background.toArgb()
        window.navigationBarColor = colors.background.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background, content = content)
    }
}

/** A page with One UI's big title, which scrolls away and leaves a small one at the top. */
@Composable
fun OneUiPage(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp)) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Text(title, fontSize = 36.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
            }
            content()
            Box(Modifier.height(24.dp))
        }
        val collapsed = scroll.value > 140
        if (collapsed || onBack != null) {
            Row(
                Modifier.fillMaxWidth()
                    .background(if (collapsed) MaterialTheme.colorScheme.background else Color.Transparent)
                    .padding(horizontal = 4.dp).height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
                } else {
                    Box(Modifier.padding(start = 16.dp))
                }
                if (collapsed) Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/**
 * [OneUiPage] for long lists. Only the rows on screen are built, so hundreds of people scroll
 * smoothly. Use [oneUiHeading], [oneUiCard] and [oneUiCardRows] for its contents.
 */
@Composable
fun OneUiLazyPage(title: String, onBack: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            item(key = "title") {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text(title, fontSize = 36.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
                }
            }
            content()
        }
        val collapsed = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 140
        Row(
            Modifier.fillMaxWidth()
                .background(if (collapsed) MaterialTheme.colorScheme.background else Color.Transparent)
                .padding(horizontal = 4.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
            } else {
                Box(Modifier.padding(start = 16.dp))
            }
            if (collapsed) Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

fun LazyListScope.oneUiHeading(key: String, title: String) {
    item(key = key) {
        Text(
            title,
            Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** A small card built in one go, for a few fixed rows. */
fun LazyListScope.oneUiCard(key: String, content: @Composable ColumnScope.() -> Unit) {
    item(key = key) { OneUiGroup(content = content) }
}

/** A card whose rows are built only when they scroll into view. */
fun <T> LazyListScope.oneUiCardRows(items: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
        val top = if (index == 0) 26.dp else 0.dp
        val bottom = if (index == items.lastIndex) 26.dp else 0.dp
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (index > 0) OneUiDivider()
            row(item)
        }
    }
}

/** A rounded card of related rows, with an optional small heading above it. */
@Composable
fun OneUiGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) {
        Text(
            title,
            Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(OneUiCardShape)
            .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

@Composable
fun OneUiRow(
    title: String,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth()
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 18.sp)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }
        if (trailing != null) Box(Modifier.padding(start = 12.dp)) { trailing() }
    }
}

@Composable
fun OneUiDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
