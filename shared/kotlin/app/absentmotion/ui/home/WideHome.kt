package app.absentmotion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.absentmotion.ui.theme.Am
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.absentmotion.library.ProjectInfo
import app.absentmotion.ui.theme.Motion

internal val WideCard = Modifier.widthIn(max = 420.dp)

@Composable
internal fun AccentPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(CircleShape).background(if (enabled) Am.Accent else Am.Accent.copy(alpha = 0.4f)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Am.OnAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
internal fun ProjectGrid(rows: List<ListRow>, upload: Boolean, onUpload: () -> Unit, state: androidx.compose.foundation.lazy.grid.LazyGridState, card: @Composable (ProjectInfo, Int, Modifier) -> Unit) {
    LazyVerticalGrid(
        if (app.absentmotion.ui.editor.LocalTablet.current) GridCells.Fixed(1) else GridCells.Adaptive(340.dp), Modifier.fillMaxSize(), state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (upload) item(key = "upload", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(bottom = 24.dp).then(WideCard)) { UploadCard(onUpload) }
        }
        itemsIndexed(
            rows, key = { _, r -> if (r is ListRow.Card) "p-" + r.info.id else "date-${(r as ListRow.Date).text}" },
            span = { _, r -> GridItemSpan(if (r is ListRow.Date) maxLineSpan else 1) },
        ) { i, row ->
            when (row) {
                is ListRow.Date -> DateLabel(row.text, first = i == 0)
                is ListRow.Card -> card(row.info, i, Modifier.animateItem(fadeInSpec = null, placementSpec = Motion.enter(), fadeOutSpec = Motion.exit()))
            }
        }
    }
}
