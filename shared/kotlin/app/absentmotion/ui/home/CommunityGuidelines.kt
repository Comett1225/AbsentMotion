package app.absentmotion.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.i18n.currentLanguage
import app.absentmotion.platform.Context
import app.absentmotion.platform.LocalContext

internal fun communityGuidelines(context: Context, tag: String): WhatsNew {
    val names = listOf("guidelines-$tag", "guidelines-${tag.substringBefore('-')}", "guidelines").distinct()
    val text = names.firstNotNullOf { name ->
        runCatching { context.assets.open("community/$name.md").bufferedReader().use { it.readText() } }.getOrNull()
    }
    return WhatsNew.parse(text)
}

@Composable
internal fun CommunityGuidelines(onClose: () -> Unit) {
    val context = LocalContext.current
    val tag = currentLanguage(context).tag
    val notes = remember(context, tag) { communityGuidelines(context, tag) }
    ToolSheet(stringResource(R.string.community_guidelines), onClose, closeButton = true) {
        MarkdownBody(notes)
    }
}
