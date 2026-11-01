package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import app.absentmotion.desk.Strings

@Composable
fun stringResource(id: Int): String = Strings.get(id)

@Composable
fun stringResource(id: Int, vararg formatArgs: Any): String = Strings.get(id, *formatArgs)

@Composable
fun pluralStringResource(id: Int, count: Int): String = Strings.plural(id, count)

@Composable
fun pluralStringResource(id: Int, count: Int, vararg formatArgs: Any): String = Strings.plural(id, count, *formatArgs)
