package app.absentmotion.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.library.ProjectInfo
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion

internal val WideCard = Modifier.widthIn(max = 420.dp)

@Composable
internal fun WideHeader(page: Int, tab: Int, onTab: (page: Int, tab: Int) -> Unit, onSettings: () -> Unit, onNew: () -> Unit, search: String?, onSearch: (String?) -> Unit) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < FullHeaderWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 20.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        if (!compact) {
            Text("AbsentMotion", color = Am.Navy, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.width(16.dp))
        }
        Chip(stringResource(R.string.home_tab_projects), page == 0 && tab == 0) { onTab(0, 0) }
        Chip(stringResource(R.string.home_tab_assets), page == 0 && tab == 1) { onTab(0, 1) }
        Chip(stringResource(R.string.home_tab_templates), page == 1) { onTab(1, tab) }
        if (search == null) Spacer(Modifier.weight(1f)) else HomeSearchField(search, page == 1, onSearch, Modifier.weight(1f))
        SearchButton(search, onSearch)
        HeaderButton(stringResource(R.string.home_new_project), HomeIcons.Plus, Am.Accent, Am.OnAccent, compact, onNew)
        HeaderButton(stringResource(R.string.settings), ChromeIcons.Gear, Am.Card, Am.Navy, compact, onSettings)
    }
}

private val FullHeaderWidth = 1080.dp

@Composable
private fun HeaderButton(label: String, icon: ImageVector, background: Color, content: Color, compact: Boolean, onClick: () -> Unit) {
    Button(
        onClick, Modifier.height(40.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = content),
        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 16.dp),
    ) {
        Icon(icon, if (compact) label else null, Modifier.size(20.dp))
        if (compact) return@Button
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun ProjectGrid(rows: List<ListRow>, upload: Boolean, onUpload: () -> Unit, card: @Composable (ProjectInfo, Int, Modifier) -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(340.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (upload) item(key = "upload", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(bottom = 24.dp).then(WideCard)) { UploadCard(onUpload) }
        }
        itemsIndexed(
            rows, key = { _, r -> if (r is ListRow.Card) r.info.id else "date-${(r as ListRow.Date).text}" },
            span = { _, r -> GridItemSpan(if (r is ListRow.Date) maxLineSpan else 1) },
        ) { i, row ->
            when (row) {
                is ListRow.Date -> DateLabel(row.text, first = i == 0)
                is ListRow.Card -> card(row.info, i, Modifier.animateItem(fadeInSpec = null, placementSpec = Motion.enter(), fadeOutSpec = Motion.exit()))
            }
        }
    }
}
