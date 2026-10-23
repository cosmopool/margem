package com.kaiodelphino.margem.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.kaiodelphino.margem.BudgetMonth
import com.kaiodelphino.margem.Ceiling
import com.kaiodelphino.margem.Cents
import com.kaiodelphino.margem.Planned
import com.kaiodelphino.margem.NoticeRow
import com.kaiodelphino.margem.PaceStatus
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.Week
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minusMonth
import kotlinx.datetime.onDay
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth
import kotlinx.datetime.toLocalDateTime
import kotlin.math.sqrt
import kotlin.time.Instant

// The components of book §8, on Compose Foundation (D15). States follow the book's rule: pressed
// darkens the surface, focus is a 2 dp anil ring 2 dp away, disabled swaps colors (never opacity).
// `pressed`/`focused` parameters force a state, for the debug catalog.

// ---- Basics: text, icon, mark ----

@Composable
fun Text(
    text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Margem.colors.ink,
    maxLines: Int = Int.MAX_VALUE, align: TextAlign = TextAlign.Unspecified, decoration: TextDecoration? = null,
) = BasicText(
    text, modifier, style.copy(color = color, textAlign = align, textDecoration = decoration),
    overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis, maxLines = maxLines,
)

@Composable
fun Text(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, color: Color = Margem.colors.ink, maxLines: Int = Int.MAX_VALUE) =
    BasicText(text, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = maxLines)

/** A book icon (`R.drawable.i_*`), drawn in [tint]. */
@Composable
fun Icon(id: Int, modifier: Modifier = Modifier, tint: Color = Margem.colors.ink, size: Dp = 24.dp) =
    Image(painterResource(id), null, modifier.size(size), colorFilter = ColorFilter.tint(tint))

/** The mark (book §3): double rule in [rule], three lines in [ink], the dot in [rule]. */
@Composable
fun Mark(size: Dp, modifier: Modifier = Modifier, ink: Color = Margem.colors.ink, rule: Color = Margem.colors.brand) =
    Canvas(modifier.size(size)) {
        val u = this.size.width / 48f
        drawLine(rule, Offset(13 * u, 6 * u), Offset(13 * u, 42 * u), 1.8f * u)
        drawLine(rule, Offset(17.5f * u, 6 * u), Offset(17.5f * u, 42 * u), 1.8f * u)
        drawLine(ink, Offset(22 * u, 15 * u), Offset(40 * u, 15 * u), 3.4f * u)
        drawLine(ink, Offset(22 * u, 24 * u), Offset(36 * u, 24 * u), 3.4f * u)
        drawLine(ink, Offset(22 * u, 33 * u), Offset(29 * u, 33 * u), 3.4f * u)
        drawCircle(rule, 2.1f * u, Offset(34 * u, 33 * u))
    }

/** Mark + "margem", for the home top bar (book §8.7). */
@Composable
fun Wordmark(modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Mark(22.dp, Modifier.padding(bottom = 1.dp))
    Text("margem", serif(24, 500, 24, italic = true, tracking = -0.02f))
}

/** Budget identity square (`.id`), in a series color. */
@Composable
fun SeriesMark(color: Color, modifier: Modifier = Modifier, size: Dp = 9.dp) = Box(modifier.size(size).background(color))

/** Focus ring: 2 dp of anil, 2 dp outside the shape. */
fun Modifier.focusRing(on: Boolean, color: Color, radius: Dp): Modifier = drawWithContent {
    drawContent()
    if (on) {
        val o = 3.dp.toPx()
        drawRoundRect(
            color, Offset(-o, -o), Size(size.width + 2 * o, size.height + 2 * o),
            CornerRadius(radius.toPx() + o), style = Stroke(2.dp.toPx()),
        )
    }
}

fun DrawScope.dashedBorder(color: Color, radius: Dp) = drawRoundRect(
    color, Offset(0.5f.dp.toPx(), 0.5f.dp.toPx()), Size(size.width - 1.dp.toPx(), size.height - 1.dp.toPx()), CornerRadius(radius.toPx()),
    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
)

/** A line of dots, as the book's `dotted` borders and the alias underline. */
private fun DrawScope.dots(color: Color, from: Offset, to: Offset, width: Float, gap: Float) = drawLine(
    color, from, to, width, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(0.01f, gap)),
)

// ---- 8.1 Buttons ----

enum class ButtonKind { PRIMARY, SECONDARY, DISMISS, TEXT }

/**
 * Book §8.1: 48 dp tall (small 40, still 48 to touch), radius 4, label Source Sans 600 15 sp.
 * Icon only when the verb is a house concept (lançar, riscar, emendar). [color] overrides the label
 * color (e.g. "Apagar" in lacre in a dialog).
 */
@Composable
fun Button(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.PRIMARY,
    icon: Int = 0, enabled: Boolean = true, small: Boolean = false, color: Color = Color.Unspecified,
    pressed: Boolean = false, focused: Boolean = false,
) {
    val c = Margem.colors
    val interaction = remember { MutableInteractionSource() }
    val isPressed = pressed || interaction.collectIsPressedAsState().value
    val isFocused = focused || interaction.collectIsFocusedAsState().value
    val bg = when {
        kind == ButtonKind.PRIMARY && !enabled -> c.surfaceSunken
        kind == ButtonKind.PRIMARY -> if (isPressed) c.actionPressed else c.action
        !enabled || !isPressed -> Color.Transparent
        kind == ButtonKind.SECONDARY -> c.surfaceSunken
        kind == ButtonKind.DISMISS -> c.overrunContainer
        else -> c.infoContainer
    }
    val fg = when {
        !enabled -> c.inkTertiary
        color != Color.Unspecified -> color
        kind == ButtonKind.PRIMARY -> c.onAction
        kind == ButtonKind.TEXT -> c.info
        else -> c.ink
    }
    val border = when {
        kind == ButtonKind.PRIMARY || kind == ButtonKind.TEXT -> Color.Transparent
        !enabled -> c.rule
        else -> c.ruleStrong
    }
    val shape = RoundedCornerShape(4.dp)
    val pad = if (kind == ButtonKind.TEXT) 12.dp else if (small) 14.dp else 20.dp
    Box(
        modifier
            .heightIn(min = Space.touch)
            .clickable(interaction, null, enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center, propagateMinConstraints = true,
    ) {
        Row(
            Modifier
                .padding(vertical = if (small) 4.dp else 0.dp)
                .focusRing(isFocused, c.info, 4.dp)
                .background(bg, shape)
                .border(1.dp, border, shape)
                .padding(horizontal = pad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != 0) Icon(icon, tint = if (kind == ButtonKind.DISMISS && enabled) c.overrun else fg, size = if (small) 18.dp else 20.dp)
            Text(label, sans(if (small) 14f else 15f, 600, 20f, 0.01f), color = fg, maxLines = 1)
        }
    }
}

/** Book `.ib`: 48 dp round touch, 24 dp icon; [dim] = ink 3 (e.g. "next month" on the current month). */
@Composable
fun IconButton(icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, dim: Boolean = false, enabled: Boolean = true) =
    Box(
        modifier.size(Space.touch).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, tint = if (dim || !enabled) Margem.colors.inkTertiary else Margem.colors.ink) }

// ---- 8.2 Fields ----

@Composable
private fun FieldLabel(text: String, color: Color) = Text(text.uppercase(), sans(11f, 600, 14f, 0.12f), color = color)

/** With [tag], the error line is tagged "$tag.error" (D35). [counter] ("72/80") sits at the right of the hint. */
@Composable
private fun FieldHint(hint: String, error: String, tag: String = "", counter: String = "", counterTag: String = "") {
    if (hint.isEmpty() && error.isEmpty() && counter.isEmpty()) return
    val c = Margem.colors
    val tagged = if (tag.isNotEmpty() && error.isNotEmpty()) Modifier.testTag("$tag.error").semantics(mergeDescendants = true) {} else Modifier
    Row(tagged.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (error.isNotEmpty()) Icon(R.drawable.i_aviso, Modifier.padding(top = 1.dp), c.overrun, 16.dp)
            Text(error.ifEmpty { hint }, sans(13.5f, 400, 18f), color = if (error.isNotEmpty()) c.overrun else c.inkTertiary)
        }
        if (counter.isNotEmpty()) Text(
            counter, sans(13f, 400, 18f, tabular = true),
            Modifier.then(if (counterTag.isEmpty()) Modifier else Modifier.testTag(counterTag)), c.inkTertiary,
        )
    }
}

/** The line under a field: 1 dp fio forte; 2 dp ink in focus; 2 dp lacre on error; dotted when read-only. */
private fun Modifier.fieldLine(focused: Boolean, error: Boolean, enabled: Boolean, c: MargemColors) = drawBehind {
    val y = size.height
    when {
        error -> drawLine(c.overrun, Offset(0f, y - 1.dp.toPx()), Offset(size.width, y - 1.dp.toPx()), 2.dp.toPx())
        !enabled -> dots(c.ruleStrong, Offset(0f, y - 0.5f.dp.toPx()), Offset(size.width, y - 0.5f.dp.toPx()), 1.dp.toPx(), 3.dp.toPx())
        focused -> drawLine(c.ink, Offset(0f, y - 1.dp.toPx()), Offset(size.width, y - 1.dp.toPx()), 2.dp.toPx())
        else -> drawLine(c.ruleStrong, Offset(0f, y - 0.5f.dp.toPx()), Offset(size.width, y - 0.5f.dp.toPx()), 1.dp.toPx())
    }
}

/**
 * Book §8.2: a printed form line, rubric on top, line below, no box. [alias] draws the text with the
 * argila dotted underline (Apelido). [italic] writes the text in Newsreader italic 500 (the legenda, §2.12).
 * [counter] ("72/80", tagged [counterTag]) sits at the right of the hint (D57). [tag] goes on the input,
 * "$tag.error" on the error line (D35).
 */
@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    placeholder: String = "", hint: String = "", error: String = "", enabled: Boolean = true,
    alias: Boolean = false, focused: Boolean = false, tag: String = "", onDone: (() -> Unit)? = null,
    italic: Boolean = false, counter: String = "", counterTag: String = "", trailing: @Composable (() -> Unit)? = null,
) {
    val c = Margem.colors
    val interaction = remember { MutableInteractionSource() }
    val isFocused = focused || interaction.collectIsFocusedAsState().value
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Column(modifier.padding(top = 8.dp, bottom = 6.dp)) {
        FieldLabel(label, if (error.isNotEmpty()) c.overrun else if (isFocused) c.ink else c.inkTertiary)
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth().then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)), enabled = enabled, readOnly = !enabled, onTextLayout = { layout = it },
            textStyle = serif(19, if (italic) 500 else 400, 24, italic = italic).copy(color = if (enabled) c.ink else c.inkTertiary),
            singleLine = true, interactionSource = interaction, cursorBrush = SolidColor(c.info),
            keyboardOptions = if (onDone == null) KeyboardOptions.Default else KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).fieldLine(isFocused, error.isNotEmpty(), enabled, c),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.weight(1f).drawBehind {
                            val l = layout ?: return@drawBehind
                            if (!alias || value.isEmpty()) return@drawBehind
                            val y = l.getLineBaseline(0) + 5.dp.toPx()
                            dots(c.brand, Offset(l.getLineLeft(0), y), Offset(l.getLineRight(0), y), 1.5f.dp.toPx(), 3.dp.toPx())
                        },
                    ) {
                        if (value.isEmpty()) Text(placeholder, serif(19, 400, 24, italic = true), color = c.inkTertiary)
                        inner()
                    }
                    trailing?.invoke()
                }
            },
        )
        FieldHint(hint, error, tag, counter, counterTag)
    }
}

/** Merchant name; with [alias], the argila dotted underline 5 dp under the baseline (book `.mer.alias`). */
@Composable
fun AliasText(text: String, style: TextStyle, modifier: Modifier = Modifier, alias: Boolean = true, color: Color = Margem.colors.ink, maxLines: Int = 1) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val brand = Margem.colors.brand
    BasicText(
        text,
        modifier.drawBehind {
            val l = layout ?: return@drawBehind
            if (!alias) return@drawBehind
            for (line in 0 until l.lineCount) {
                val y = l.getLineBaseline(line) + 5.dp.toPx()
                dots(brand, Offset(l.getLineLeft(line), y), Offset(l.getLineRight(line), y), 1.5f.dp.toPx(), 3.dp.toPx())
            }
        },
        style.copy(color = color), onTextLayout = { layout = it }, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * "em Padaria do Zé" (spec §2.12): the credit under a legenda headline — "em" in sans ink 3, then the
 * merchant name with the Apelido dotted underline (book `.by`).
 */
@Composable
fun CreditLine(name: String, alias: Boolean, color: Color = Margem.colors.inkSecondary, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("em", sans(13f, 400, 20f), color = Margem.colors.inkTertiary)
        AliasText(name, serif(15, 500, 20), alias = alias, color = color, maxLines = 1)
    }
}

/** Digits typed into the money field become cents: 2-3-9-0 = R$ 23,90 (book §8.2). */
private class CentsTransformation(val ink: Color, val dim: Color) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val shown = buildAnnotatedString {
            if (digits.isEmpty()) withStyle(SpanStyle(color = dim)) { append("0,00") }
            else {
                val (int, cts) = moneyParts(Cents(digits.toLong()))
                append(int)
                withStyle(SpanStyle(color = ink)) { append(",$cts") }
            }
        }
        return withTypedTail(shown, digits.length)
    }
}

/**
 * The typed digits map onto zero-width spaces after [shown]: the keyboard's composition (kept, see [MoneyField])
 * lands on them, so Compose's composition underline is zero wide and the cursor anchor finds real characters.
 * A tap anywhere puts the cursor at the end, where the next digit goes.
 */
private fun withTypedTail(shown: AnnotatedString, typed: Int) = TransformedText(shown + AnnotatedString("\u200B".repeat(typed)), object : OffsetMapping {
    override fun originalToTransformed(offset: Int) = shown.length + offset
    override fun transformedToOriginal(offset: Int) = typed
})

/**
 * Book §8.2 money field: "R$" in the sans, the value in Newsreader [size] sp, filled from the cents.
 * [original] ≠ null shows the emenda line ("‸ emendado · o recado dizia R$ 32,90 · Desfazer").
 * [tag] goes on the input, "$tag.error" on the error line (D35).
 */
@Composable
fun MoneyField(
    cents: Cents, onChange: (Cents) -> Unit, label: String, modifier: Modifier = Modifier,
    hint: String = "", error: String = "", size: Int = 46, original: Cents? = null, onUndo: () -> Unit = {},
    focused: Boolean = false, tag: String = "",
) {
    val c = Margem.colors
    val interaction = remember { MutableInteractionSource() }
    val isFocused = focused || interaction.collectIsFocusedAsState().value
    val digits = if (cents.v == 0L) "" else cents.v.toString()
    // The keyboard's value is kept as is: dropping its composition (FlorisBoard composes the whole number)
    // restarts the input on every recomposition and loses the digits typed meanwhile. [cents] from outside
    // (Desfazer, a loaded value) replaces it.
    var typed by remember { mutableStateOf(TextFieldValue(digits, androidx.compose.ui.text.TextRange(digits.length))) }
    Column(modifier.padding(top = 8.dp, bottom = 6.dp)) {
        FieldLabel(label, if (error.isNotEmpty()) c.overrun else if (isFocused) c.ink else c.inkTertiary)
        BasicTextField(
            if (typed.text == digits) typed else TextFieldValue(digits, androidx.compose.ui.text.TextRange(digits.length)),
            { v ->
                val clean = v.text.filter(Char::isDigit).trimStart('0').take(12)
                typed = if (clean == v.text) v else TextFieldValue(clean, androidx.compose.ui.text.TextRange(clean.length))
                onChange(Cents(clean.ifEmpty { "0" }.toLong()))
            },
            Modifier.fillMaxWidth().then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)),
            textStyle = serif(size, 400, size + 6, tabular = true).copy(color = c.ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, interactionSource = interaction, cursorBrush = SolidColor(c.info),
            visualTransformation = CentsTransformation(c.inkSecondary, c.inkTertiary),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = (size + 22).dp).padding(top = 10.dp).fieldLine(isFocused, error.isNotEmpty(), true, c),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Book `.fld.money .in{align-items:baseline}`: "R$" sits on the value's baseline.
                    Text("R$", sans(17f, 500, 20f), Modifier.alignByBaseline().padding(end = 10.dp), color = c.inkSecondary)
                    Box(Modifier.alignByBaseline()) { inner() }
                }
            },
        )
        if (original != null) Emenda(original, onUndo)
        FieldHint(hint, error, tag)
    }
}

/** "‸ emendado · o recado dizia ~~R$ 32,90~~ · Desfazer" (book §8.2). */
@Composable
fun Emenda(original: Cents, onUndo: () -> Unit, tag: String = "") = Emenda(money(original), onUndo, tag)

/**
 * "‸ emendado · o recado dizia ~~[original]~~ · Desfazer": the value, the bank text or the date (book §8.2,
 * §8.9). [tag]: the line ("$tag", merged) and "$tag.undo" on Desfazer (D35).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Emenda(original: String, onUndo: () -> Unit, tag: String = "") {
    val c = Margem.colors
    FlowRow(
        Modifier.padding(top = 8.dp).then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag).semantics(mergeDescendants = true) {}),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‸ emendado", serif(14, 600, 18, italic = true), color = c.brand)
        Text("o recado dizia", sans(13.5f, 400, 18f), color = c.inkSecondary)
        Text(original, sans(13.5f, 400, 18f, tabular = true), color = c.inkTertiary, decoration = TextDecoration.LineThrough)
        Text(
            "Desfazer", sans(13.5f, 600, 18f),
            Modifier.then(if (tag.isEmpty()) Modifier else Modifier.testTag("$tag.undo")).clickable(role = Role.Button, onClick = onUndo).padding(vertical = 4.dp), color = c.info,
        )
    }
}

/** Book `.stepper`: − n +, for Parcelas and "Mês começa no dia". [tag]: the number, "$tag.minus", "$tag.plus" (D35). */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, range: IntRange, modifier: Modifier = Modifier, tag: String = "") {
    val c = Margem.colors
    Row(modifier.border(1.dp, c.ruleStrong, RoundedCornerShape(4.dp)), verticalAlignment = Alignment.CenterVertically) {
        StepperKey("−", value > range.first, if (tag.isEmpty()) "" else "$tag.minus") { onChange(value - 1) }
        Text(
            value.toString(), serif(22, 500, 28, tabular = true),
            Modifier.then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)).widthIn(min = 56.dp).height(44.dp).drawBehind {
                drawLine(c.rule, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                drawLine(c.rule, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
            }.padding(top = 8.dp, start = 8.dp, end = 8.dp),
            align = TextAlign.Center,
        )
        StepperKey("+", value < range.last, if (tag.isEmpty()) "" else "$tag.plus") { onChange(value + 1) }
    }
}

@Composable
private fun StepperKey(sign: String, enabled: Boolean, tag: String, onClick: () -> Unit) = Box(
    Modifier.size(48.dp, 44.dp).then(if (tag.isEmpty()) Modifier else Modifier.testTag(tag)).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(sign, sans(20f, 500, 24f), color = if (enabled) Margem.colors.ink else Margem.colors.inkTertiary) }

/**
 * Book `.days`: the seven days, the chosen one in ink and `selected` ("Semana começa na"). [tag]: each day
 * is "$tag" + its DayOfWeek name in lowercase (D35).
 */
@Composable
fun DayPicker(selected: DayOfWeek, onSelect: (DayOfWeek) -> Unit, modifier: Modifier = Modifier, tag: String = "") {
    val c = Margem.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (d in DayOfWeek.entries) {
            val on = d == selected
            Box(
                Modifier.size(38.dp, 48.dp).then(if (tag.isEmpty()) Modifier else Modifier.testTag("$tag${d.name.lowercase()}"))
                    .semantics { this.selected = on }.clickable(role = Role.RadioButton) { onSelect(d) }.padding(vertical = 2.dp)
                    .background(if (on) c.ink else Color.Transparent, RoundedCornerShape(3.dp))
                    .border(1.dp, if (on) c.ink else c.rule, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(weekdayShort(d), sans(12f, 600, 16f), color = if (on) c.paper else c.inkSecondary) }
        }
    }
}

/** Book `.check`: 20 dp, ink with a check when on, a fio forte square when off. */
@Composable
fun Check(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Margem.colors
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier.size(20.dp).background(if (checked) c.ink else Color.Transparent, shape).border(1.dp, if (checked) c.ink else c.ruleStrong, shape),
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(R.drawable.i_lancar, tint = c.paper, size = 16.dp) }
}

// ---- 8.3 Tags ----

enum class ChipState { NORMAL, SELECTED, SUGGESTED, DISABLED }

/**
 * Book §8.3: a paper tag with a hole, radius 3, 32 dp drawn and 48 dp to touch. Suggested (from the
 * merchant's last transaction) is dashed: it counts, and one touch takes it off (spec §2.5). Names are lowercase, as written by hand.
 */
@Composable
fun TagChip(
    name: String, modifier: Modifier = Modifier, state: ChipState = ChipState.NORMAL, removable: Boolean = false,
    focused: Boolean = false, onClick: () -> Unit = {},
) {
    val c = Margem.colors
    val interaction = remember { MutableInteractionSource() }
    val isFocused = focused || interaction.collectIsFocusedAsState().value
    val fg = when (state) {
        ChipState.NORMAL -> c.ink
        ChipState.SELECTED -> c.paper
        ChipState.SUGGESTED -> c.inkSecondary
        ChipState.DISABLED -> c.inkTertiary
    }
    val bg = when (state) {
        ChipState.NORMAL -> c.surface
        ChipState.SELECTED -> c.ink
        else -> Color.Transparent
    }
    val dashed = state == ChipState.SUGGESTED
    val border = when (state) {
        ChipState.SELECTED -> c.ink
        ChipState.DISABLED -> c.rule
        else -> c.ruleStrong
    }
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier.heightIn(min = Space.touch).clickable(interaction, null, state != ChipState.DISABLED, role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(32.dp).focusRing(isFocused, c.info, 3.dp).background(bg, shape)
                .then(if (dashed) Modifier.drawBehind { dashedBorder(border, 3.dp) } else Modifier.border(1.dp, border, shape))
                .padding(start = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Canvas(Modifier.size(7.dp)) {
                if (state == ChipState.SELECTED) drawCircle(fg, 3.dp.toPx())
                else drawCircle(fg.copy(alpha = 0.75f), (3.5f - 0.75f).dp.toPx(), style = Stroke(1.5f.dp.toPx()))
            }
            Text(name, sans(14f, 500, 16f), color = fg, maxLines = 1)
            if (removable) Text("✕", sans(12f, 400, 14f), Modifier.padding(start = 2.dp), color = fg.copy(alpha = 0.7f))
        }
    }
}

// ---- 8.4 Notice card ----

/** "07:42" of a notice, in the device's zone. */
fun noticeTime(postedAt: Long): String = time(Instant.fromEpochMilliseconds(postedAt).toLocalDateTime(TimeZone.currentSystemDefault()).time)

/**
 * Book §8.4: source, time, the bank text quoted in italics, merchant (alias underlined) and value read,
 * and the two buttons. New = argila double rule on the left. Estorno: "Registrar estorno" and the value
 * without color. Looks like an ad: dashed card, the flag, "Lançar mesmo assim" and Riscar as primary.
 * With [onRestore] (Riscados), "Trazer de volta" is the one button. No gestures: only the buttons.
 */
@Composable
fun NoticeCard(
    notice: NoticeRow, onAccept: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    time: String = noticeTime(notice.raw.postedAt), acceptEnabled: Boolean = true,
    acceptTag: String = "", dismissTag: String = "", newTag: String = "",
    onRestore: (() -> Unit)? = null, restoreTag: String = "",
    planned: Planned = Planned(), onNotIt: () -> Unit = {}, bandTag: String = "", notItTag: String = "", onBody: (() -> Unit)? = null,
) {
    val c = Margem.colors
    val ad = notice.looksLikeAd
    val recognized = planned.occurrence.v != 0L
    val refund = notice.parsed.isRefund
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.fillMaxWidth()
            .background(if (ad) Color.Transparent else c.surface, shape)
            .then(if (ad) Modifier.drawBehind { dashedBorder(c.rule, 8.dp) } else Modifier.border(1.dp, c.rule, shape))
            .drawBehind {
                if (!notice.isNew) return@drawBehind
                val top = 14.dp.toPx(); val bottom = size.height - 14.dp.toPx()
                for (x in listOf(8.5f, 12.5f)) drawLine(c.brand, Offset(x.dp.toPx(), top), Offset(x.dp.toPx(), bottom), 1.dp.toPx())
            }
            .then(if (onBody != null) Modifier.clickable(onClick = onBody) else Modifier)
            .padding(start = if (notice.isNew) 24.dp else 16.dp, top = 12.dp, end = 14.dp, bottom = 4.dp),
    ) {
        // The double rule of a new notice, as a node of its own for the tests (D35).
        if (notice.isNew && newTag.isNotEmpty()) Spacer(Modifier.size(0.dp).semantics(mergeDescendants = true) { testTag = newTag })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(notice.raw.appLabel.uppercase(), sans(11f, 600, 14f, 0.1f), Modifier.weight(1f), c.inkTertiary, maxLines = 1)
            Text(time, sans(11f, 600, 14f, 0.1f, tabular = true), color = c.inkTertiary)
        }
        Text("“${notice.raw.body}”", serif(14, 400, 20, italic = true), Modifier.padding(top = 5.dp, bottom = 9.dp), c.inkSecondary, maxLines = 4)
        if (ad) {
            Text(
                "▲ Parece propaganda", sans(12f, 600, 16f),
                Modifier.background(c.aheadContainer, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 4.dp), c.ahead,
            )
        } else {
            Row(
                Modifier.fillMaxWidth().drawBehind { dots(c.ruleStrong, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx(), 3.dp.toPx()) }.padding(top = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val name = notice.alias.ifEmpty { notice.parsed.merchant }
                    AliasText(name, serif(19, 500, 24), Modifier.weight(1f, fill = false), alias = notice.alias.isNotEmpty())
                    if (refund) Text("ESTORNO", sans(12f, 600, 16f, 0.12f), Modifier.padding(bottom = 3.dp), c.inkTertiary)
                }
                if (notice.parsed.cents.v > 0) Amount(notice.parsed.cents, color = if (refund) c.inkSecondary else c.ink)
            }
        }
        // Spec §2.13, book 8.4: the app recognizing the recado as a previsto, in anil.
        if (recognized) InfoBand(
            R.drawable.i_ligar, "É o previsto ${planned.name}", plannedLine(planned) + " · dia ${planned.day}",
            Modifier.padding(top = 10.dp).then(if (bandTag.isEmpty()) Modifier else Modifier.testTag(bandTag)),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            val accept = if (acceptTag.isEmpty()) Modifier else Modifier.testTag(acceptTag)
            val dismiss = if (dismissTag.isEmpty()) Modifier else Modifier.testTag(dismissTag)
            when {
                recognized && onRestore == null -> {
                    Button("Não é", onNotIt, if (notItTag.isEmpty()) Modifier else Modifier.testTag(notItTag), kind = ButtonKind.TEXT, small = true)
                    Box(
                        dismiss.size(Space.touch).semantics { contentDescription = "Riscar" }.clickable(role = Role.Button, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(40.dp).border(1.dp, c.ruleStrong, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            Icon(R.drawable.i_riscar, tint = c.overrun, size = 18.dp)
                        }
                    }
                    Button("Lançar previsto", onAccept, accept, icon = R.drawable.i_lancar, small = true, enabled = acceptEnabled)
                }
                // Riscados: the one action is bringing it back to the Pauta.
                onRestore != null -> Button(
                    "Trazer de volta", onRestore, if (restoreTag.isEmpty()) Modifier else Modifier.testTag(restoreTag),
                    kind = ButtonKind.SECONDARY, icon = R.drawable.i_desfazer, small = true,
                )
                ad -> {
                    Button("Lançar mesmo assim", onAccept, accept, kind = ButtonKind.TEXT, small = true, enabled = acceptEnabled)
                    Button("Riscar", onDismiss, dismiss, icon = R.drawable.i_riscar, small = true)
                }
                refund -> {
                    Button("Riscar", onDismiss, dismiss, kind = ButtonKind.DISMISS, icon = R.drawable.i_riscar, small = true)
                    Button("Registrar estorno", onAccept, accept, icon = R.drawable.i_estorno, small = true, enabled = acceptEnabled)
                }
                else -> {
                    Button("Riscar", onDismiss, dismiss, kind = ButtonKind.DISMISS, icon = R.drawable.i_riscar, small = true)
                    Button("Lançar", onAccept, accept, small = true, enabled = acceptEnabled)
                }
            }
        }
    }
}

/** Book `.amt`: "R$" in the sans at 70% in ink 2, the value in Newsreader 500, tabular. */
@Composable
fun Amount(cents: Cents, modifier: Modifier = Modifier, size: Int = 19, color: Color = Margem.colors.ink, struck: Boolean = false) {
    val c = Margem.colors
    val (int, cts) = moneyParts(cents)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = sourceSans(), fontSize = 0.7.em, color = c.inkSecondary, fontFeatureSettings = TABULAR)) { append("R$$NNBSP") }
            append("$int,$cts")
        },
        serif(size, 500, size + 5, tabular = true).copy(textDecoration = if (struck) TextDecoration.LineThrough else null),
        modifier, color, maxLines = 1,
    )
}

// ---- 8.5 Meter ----

/** Book `.st`: state in words, with its own shape (circle, triangle, hatch) — never color alone. */
@Composable
fun StatusLabel(status: PaceStatus, modifier: Modifier = Modifier, empty: Boolean = false) {
    val c = Margem.colors
    val (label, color) = when {
        empty -> "Sem gasto" to c.inkTertiary
        status == PaceStatus.ON_PACE -> "No ritmo" to c.onPace
        status == PaceStatus.AHEAD -> "Acima do ritmo" to c.ahead
        else -> "Fora da margem" to c.overrun
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(8.dp)) {
            when {
                empty -> drawCircle(color, size.width / 2 - 0.75f.dp.toPx(), style = Stroke(1.5f.dp.toPx()))
                status == PaceStatus.ON_PACE -> drawCircle(color)
                status == PaceStatus.AHEAD -> drawPath(Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }, color)
                else -> hatch(color, Offset.Zero, size, 1.5f.dp.toPx(), 3.dp.toPx())
            }
        }
        Text(label, sans(13f, 600, 16f), color = color)
    }
}

/** Lacre hatch, -45°, with a 1 dp inset border (book `.over`, `.st.ov`). */
internal fun DrawScope.hatch(color: Color, at: Offset, area: Size, line: Float, period: Float) {
    clipRect(at.x, at.y, at.x + area.width, at.y + area.height) {
        val step = period * sqrt(2f)
        var x = at.x - area.height
        while (x < at.x + area.width) {
            drawLine(color, Offset(x, at.y + area.height), Offset(x + area.height, at.y), line)
            x += step
        }
    }
    val b = 0.5f.dp.toPx()
    drawRect(color, Offset(at.x + b, at.y + b), Size(area.width - 2 * b, area.height - 2 * b), style = Stroke(1.dp.toPx()))
}

/**
 * Book §8.5, the budget's ruler: a 2 dp rail (100% of the limit), the spent ink in the state's color,
 * a 2 dp mark at today's ideal pace, the argila double rule at the limit and, past it, 9% reserved for
 * the excess hatch (up to 10% drawn; above that, the number speaks). [tall] = the detail's 22 dp ruler.
 */
@Composable
fun Meter(spent: Cents, limit: Cents, ideal: Cents, status: PaceStatus, modifier: Modifier = Modifier, tall: Boolean = false) {
    val c = Margem.colors
    val description = "${money(spent)} de ${money(limit)}"
    Canvas(modifier.fillMaxWidth().height(if (tall) 22.dp else 14.dp).semantics { contentDescription = description }) {
        val h = size.height
        val rail = size.width / 1.09f
        val l = limit.v.toFloat()
        val g = if (l <= 0f) (if (spent.v > 0) 1f else 0f) else (spent.v / l).coerceIn(0f, 1f)
        val p = if (l <= 0f) 0f else (ideal.v / l).coerceIn(0f, 1f)
        val railY = if (tall) 10.dp.toPx() else 6.dp.toPx()
        drawRect(c.rule, Offset(0f, railY), Size(rail, 2.dp.toPx()))
        val fillTop = if (tall) 5.dp.toPx() else 3.dp.toPx()
        val fillH = if (tall) 12.dp.toPx() else 8.dp.toPx()
        val fill = when (status) {
            PaceStatus.ON_PACE -> c.onPace
            PaceStatus.AHEAD -> c.ahead
            PaceStatus.OVERRUN -> c.overrun
        }
        if (g > 0f) drawRect(fill, Offset(0f, fillTop), Size(if (status == PaceStatus.OVERRUN) rail else g * rail, fillH))
        if (status == PaceStatus.OVERRUN && l > 0f) {
            val x = ((spent.v - limit.v) / l).coerceIn(0f, 0.1f)
            hatch(c.overrun, Offset(rail + 3.dp.toPx(), fillTop), Size(x * rail, fillH), 1.6f.dp.toPx(), 4.dp.toPx())
        }
        drawRect(c.ink, Offset(p * rail - 1.dp.toPx(), -1.dp.toPx()), Size(2.dp.toPx(), h + 2.dp.toPx()))
        for (x in listOf(rail - 1.5f.dp.toPx(), rail + 2.5f.dp.toPx())) drawLine(c.brand, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
    }
}

@Composable
fun Meter(month: BudgetMonth, modifier: Modifier = Modifier, tall: Boolean = false) =
    Meter(month.spent, month.limit, month.ideal, month.status, modifier, tall)

/**
 * Book §8.10, the ceiling line: a meter without pace. 1 dp rail in fio forte, the limits in a 3 dp ink
 * stroke (not spending: no state color), the argila double rule at the ceiling and, past it, the same 9%
 * reserved for the excess hatch (up to 10% drawn).
 */
@Composable
fun CeilingMeter(limits: Cents, ceiling: Cents, modifier: Modifier = Modifier) {
    val c = Margem.colors
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val rail = size.width / 1.09f
        val t = ceiling.v.toFloat()
        val g = if (t <= 0f) 0f else (limits.v / t).coerceIn(0f, 1f)
        drawRect(c.ruleStrong, Offset(0f, 4.dp.toPx()), Size(rail, 1.dp.toPx()))
        if (g > 0f) drawRect(c.ink, Offset(0f, 3.dp.toPx()), Size(g * rail, 3.dp.toPx()))
        if (limits > ceiling && t > 0f) {
            val x = ((limits.v - ceiling.v) / t).coerceIn(0f, 0.1f)
            // Kept inside the 9% reserved: 10% of the rail after the 3 dp gap would cross the page margin.
            val w = minOf(x * rail, size.width - rail - 3.dp.toPx())
            hatch(c.overrun, Offset(rail + 3.dp.toPx(), 2.dp.toPx()), Size(w, 5.dp.toPx()), 1.6f.dp.toPx(), 4.dp.toPx())
        }
        for (x in listOf(rail - 1.5f.dp.toPx(), rail + 2.5f.dp.toPx())) drawLine(c.brand, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
}

/**
 * Book §8.10 under the Verbas header: the ceiling line with "cotas R$ 3.720,00 · teto R$ 4.100,00" ("· só
 * outubro" when the month's ceiling isn't the default, "+ 300" in lacre above it), or, without a ceiling,
 * the dotted rule with "As cotas somam R$ 3.720,00." and "Pôr um teto". Draws only: the header is the target.
 */
@Composable
fun CeilingLegend(ceiling: Ceiling, modifier: Modifier = Modifier) {
    val c = Margem.colors
    val style = sans(12f, 600, 14.4f, tabular = true)
    if (ceiling.cents.v == 0L) {
        Row(
            modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 44.dp).drawBehind {
                drawLine(c.ruleStrong, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 1.dp.toPx())))
            },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("As cotas somam ${money(ceiling.limits)}.", sans(13.5f, 400, 17.5f, tabular = true), color = c.inkTertiary)
            Text("Pôr um teto", sans(14f, 600, 20f, 0.01f), Modifier.padding(start = 12.dp), color = c.info)
        }
        return
    }
    val over = ceiling.over.v > 0
    val only = if (ceiling.cents != ceiling.standard) " · só ${monthLong(ceiling.month.month)}" else ""
    Column(modifier.fillMaxWidth()) {
        CeilingMeter(ceiling.limits, ceiling.cents, Modifier.padding(top = 10.dp))
        // Book `.tlab`: the legend ends under the double rule (9% reserved), or spans it when "+ 300" is there.
        Row(Modifier.padding(top = 5.dp).fillMaxWidth(if (over) 1f else 1f / 1.09f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("cotas ${money(ceiling.limits)}", style, Modifier.weight(1f), c.inkTertiary, maxLines = 1)
            Text("teto ${money(ceiling.cents)}$only", style, if (over) Modifier else Modifier.offset(x = 5.dp), c.inkTertiary, maxLines = 1)
            if (over) Text("+ ${stepValue(ceiling.over)}", style, Modifier.widthIn(min = 30.dp), c.overrun, align = TextAlign.End, maxLines = 1)
        }
    }
}

// ---- 8.6 Month and weekly steps ----

/**
 * Book §8.6 month selector: ‹ "outubro de 2026" / "1 A 31 · MÊS ATUAL" ›. An arrow that can't move is in
 * ink 3 and disabled. [tag]: "$tag.prev", "$tag.label", "$tag.next" (D35).
 */
@Composable
fun MonthPicker(
    month: YearMonth, sub: String, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier,
    canNext: Boolean = true, canPrev: Boolean = true, tag: String = "",
) = Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(R.drawable.i_voltar, onPrev, Modifier.testTag("$tag.prev"), dim = !canPrev, enabled = canPrev)
    Column(Modifier.weight(1f).testTag("$tag.label").semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(monthTitle(month), serif(17, 500, 22), align = TextAlign.Center)
        Text(sub.uppercase(), sans(12f, 500, 16f, 0.04f), color = Margem.colors.inkTertiary, align = TextAlign.Center)
    }
    IconButton(R.drawable.i_avancar, onNext, Modifier.testTag("$tag.next"), dim = !canNext, enabled = canNext)
}

/** "1–4", "26–31": the days of a week cut to the month. */
fun weekRange(w: Week) = "${w.start.day}–${w.end.day}"

/** Whole reais when there are no cents ("105"), else "33,33": the steps' grid. */
fun stepValue(c: Cents) = if (c.v % 100 == 0L) moneyParts(c).first else moneyPlain(c)

/**
 * Book §8.6/10.8, the weekly steps editor: one cell per week; this week highlighted with an ink rule;
 * the locked week (the only one left automatic, spec §4) in ink 3 and not editable. Tapping another
 * week calls [onEdit]; edited weeks are marked with argila. [tag]: each cell is "$tag${week.start}" (D35).
 */
@Composable
fun StepsEditor(weeks: List<Week>, today: LocalDate, onEdit: (Week) -> Unit, modifier: Modifier = Modifier, tag: String = "") {
    val c = Margem.colors
    Row(modifier.fillMaxWidth().drawBehind { drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }) {
        for (w in weeks) {
            val current = today >= w.start && today <= w.end
            val auto = w.locked
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).testTag("$tag${w.start}")
                    .clickable(enabled = !auto, role = Role.Button) { onEdit(w) }
                    .then(if (current) Modifier.background(c.surfaceSunken).drawBehind { drawRect(c.ink, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) } else Modifier)
                    .padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(weekRange(w), sans(10.5f, if (current) 700 else 600, 14f), color = if (current) c.ink else c.inkTertiary, align = TextAlign.Center)
                Text(stepValue(w.step), serif(16, 500, 20, tabular = true), Modifier.padding(top = 2.dp), if (auto) c.inkTertiary else c.ink, maxLines = 1)
                if (w.edited) Box(Modifier.padding(top = 3.dp).size(4.dp).background(c.brand, RoundedCornerShape(2.dp)))
            }
        }
    }
}

// ---- 8.7 Navigation ----

/** Book §8.7, top bar: 56 dp, title in Newsreader 21 sp; [onBack] adds the back (or close) arrow. */
@Composable
fun TopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, close: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) =
    Row(modifier.fillMaxWidth().height(56.dp).padding(start = if (onBack == null) 16.dp else 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(if (close) R.drawable.i_fechar else R.drawable.i_voltar, onBack)
        Text(title, if (onBack == null) serif(24, 500, 30) else serif(21, 500, 26), Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
        actions()
    }

/** Verbas, the home screen, shows the signature instead of a title. */
@Composable
fun HomeBar(modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) =
    Row(modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Wordmark(Modifier.weight(1f))
        actions()
    }

enum class Tab(val label: String, val icon: Int) {
    BUDGETS("Verbas", R.drawable.i_verba),
    INBOX("Pauta", R.drawable.i_pauta),
    TRANSACTIONS("Lançamentos", R.drawable.i_lancamento),
    INSIGHTS("Leitura", R.drawable.i_leitura),
    SETTINGS("Ajustes", R.drawable.i_ajustes),
}

/**
 * Book §8.7, bottom bar: 72 dp, five destinations, labels always visible. Active: ink 700 and a 2 dp
 * rule on top, like a bookmark ribbon. The Pauta counter is the bar's only argila.
 */
@Composable
fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier, badge: Int = 0) {
    val c = Margem.colors
    Row(
        modifier.fillMaxWidth().background(c.paper)
            .drawBehind { drawLine(c.rule, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars).height(72.dp),
    ) {
        for (tab in Tab.entries) {
            val on = tab == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clickable(role = Role.Tab) { onSelect(tab) }
                    .drawBehind { if (on) drawRect(c.ink, Offset(size.width * 0.24f, -1.dp.toPx()), Size(size.width * 0.52f, 2.dp.toPx())) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(tab.icon, tint = if (on) c.ink else c.inkTertiary)
                    Text(tab.label, sans(11.5f, if (on) 700 else 500, 14f), color = if (on) c.ink else c.inkTertiary, maxLines = 1)
                }
                if (tab == Tab.INBOX && badge > 0) Text(
                    badge.toString(), sans(11f, 700, 18f, tabular = true),
                    Modifier.align(Alignment.TopCenter).offset(x = 16.dp, y = 9.dp).heightIn(min = 18.dp).widthIn(min = 18.dp)
                        .background(c.brand, RoundedCornerShape(9.dp)).padding(horizontal = 5.dp),
                    c.surface, align = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Book §8.7 selector: a few short options side by side; the active one in ink 700 with a 2 dp rule
 * below, and `selected`. "Ordenar" in Verbas, theme and widget order in Ajustes. [tag]: each option is
 * "$tag" + its value in lowercase (D35).
 */
@Composable
fun <T> OptionPicker(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, tag: String = "") {
    val c = Margem.colors
    Row(modifier.fillMaxWidth().drawBehind { drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).testTag("$tag${value.toString().lowercase()}")
                    .semantics { this.selected = on }.clickable(role = Role.RadioButton) { onSelect(value) }
                    .drawBehind { if (on) drawRect(c.ink, Offset(size.width * 0.14f, size.height - 1.dp.toPx()), Size(size.width * 0.72f, 2.dp.toPx())) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, sans(14f, if (on) 700 else 500, 18f), color = if (on) c.ink else c.inkTertiary, maxLines = 1) }
        }
    }
}

// ---- Page pieces ----

/** Rubric, headline and dek: how each screen opens, like a magazine page (book §10). */
@Composable
fun PageHeader(overline: String, headline: String, modifier: Modifier = Modifier, dek: String = "") = Column(modifier) {
    Text(overline.uppercase(), sans(11f, 600, 14f, 0.12f), color = Margem.colors.inkTertiary)
    Text(headline, serif(29, 400, 33, tracking = -0.01f), Modifier.padding(top = 6.dp, bottom = 8.dp))
    if (dek.isNotEmpty()) Text(dek, sans(15f, 400, 22f), color = Margem.colors.inkSecondary)
}

/** Book `.secline`: a section rubric over an ink rule, with an optional aside on the right. */
@Composable
fun SectionLine(title: String, modifier: Modifier = Modifier, aside: String = "") {
    val c = Margem.colors
    Row(
        modifier.fillMaxWidth().padding(top = 18.dp).drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }.padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title.uppercase(), sans(11f, 600, 14f, 0.12f))
        if (aside.isNotEmpty()) Text(aside.uppercase(), sans(11f, 600, 14f, 0.12f), color = c.inkTertiary)
    }
}

/** Book `.dayh`: "HOJE · SEXTA, 9 DE OUTUBRO" followed by a rule, grouping a list by day. */
@Composable
fun DayHeader(text: String, modifier: Modifier = Modifier) {
    val c = Margem.colors
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text.uppercase(), sans(11f, 600, 14f, 0.12f), color = c.inkTertiary)
        Box(Modifier.weight(1f).height(1.dp).background(c.rule))
    }
}

/** Book `.lrow`: a list line with a Newsreader title, a small line below and something on the right. */
@Composable
fun ListRow(
    title: String, modifier: Modifier = Modifier, sub: String = "", onClick: (() -> Unit)? = null, divider: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val c = Margem.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind { if (divider) drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, serif(17, 500, 22), maxLines = 2)
            if (sub.isNotEmpty()) Text(sub, sans(13f, 400, 17f), Modifier.padding(top = 2.dp), c.inkTertiary, maxLines = 2)
        }
        trailing()
    }
}

/** Book §7.3 empty state: icon, Newsreader title, a sentence, maybe one action. */
@Composable
fun EmptyState(icon: Int, title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) =
    Column(modifier.fillMaxWidth().padding(top = Space.s8)) {
        Icon(icon, tint = Margem.colors.inkTertiary, size = 32.dp)
        Text(title, Margem.type.display, Modifier.padding(top = 10.dp))
        Text(text, Margem.type.body, Modifier.padding(top = 6.dp, bottom = 14.dp), Margem.colors.inkSecondary)
        action()
    }

/**
 * Book §7.3 notice box (aviso): icon in its state color, a Newsreader sentence, the explanation and
 * the actions. "A anotação automática está pausada." uses it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Notice(icon: Int, iconTint: Color, title: String, text: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Margem.colors
    Column(modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(8.dp)).border(1.dp, c.rule, RoundedCornerShape(8.dp)).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        Icon(icon, tint = iconTint, size = 32.dp)
        Text(title, serif(26, 400, 30), Modifier.padding(top = 10.dp))
        Text(text, Margem.type.body, Modifier.padding(top = 6.dp, bottom = 6.dp), c.inkSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Row(content = actions) }
    }
}

/** "A anotação automática está pausada." (glossary: Anotação pausada; spec §7.2). */
@Composable
fun CapturePaused(onReconnect: () -> Unit, modifier: Modifier = Modifier, onManual: (() -> Unit)? = null) = Notice(
    R.drawable.i_pausa, Margem.colors.ahead, "A anotação automática está pausada.",
    "Sem acesso às notificações, o Margem não vê suas compras. Você pode religar ou criar um lançamento manual.",
    modifier,
) {
    Button("Religar acesso", onReconnect)
    if (onManual != null) Button("Novo lançamento", onManual, kind = ButtonKind.TEXT)
}

// ---- 8.11 Previsto ----

/** "Casa · contas, internet": budget and tags of a previsto, as its band and rows say them. */
fun plannedLine(p: Planned): String = listOf(p.budgetName, p.tags.joinToString(", ")).filter(String::isNotEmpty).joinToString(" · ")

/**
 * Book 8.11 `.liga`: the app recognizing (anil container, never a state color): [icon] in anil, a serif [title]
 * when given, then [text] and an optional text [action] ("Desligar").
 */
@Composable
fun InfoBand(icon: Int, title: String, text: String, modifier: Modifier = Modifier, action: String = "", onAction: () -> Unit = {}, actionTag: String = "") {
    val c = Margem.colors
    Row(
        modifier.fillMaxWidth().background(c.infoContainer, RoundedCornerShape(6.dp)).semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, Modifier.padding(top = 2.dp), tint = c.info, size = 18.dp)
        Column(Modifier.weight(1f)) {
            if (title.isNotEmpty()) Text(title, serif(16, 500, 20))
            Text(text, sans(13.5f, 400, 18f), color = c.inkSecondary)
            if (action.isNotEmpty()) Text(
                action, sans(13.5f, 600, 18f), Modifier.then(if (actionTag.isEmpty()) Modifier else Modifier.testTag(actionTag))
                    .clickable(role = Role.Button, onClick = onAction).padding(vertical = 6.dp),
                c.info,
            )
        }
    }
}

/** Book 8.11 `.dia`: the dashed day stamp — "still to happen". Late: the day in ink 3. */
@Composable
fun DayStamp(date: LocalDate, late: Boolean, modifier: Modifier = Modifier) {
    val c = Margem.colors
    Column(
        modifier.width(42.dp).drawBehind { dashedBorder(c.ruleStrong, 3.dp) }.padding(top = 3.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(monthShort(date.month).uppercase(), sans(9.5f, 700, 12f, 0.12f), color = c.inkTertiary)
        Text(date.day.toString(), serif(20, 500, 21, tabular = true), color = if (late) c.inkTertiary else c.ink)
    }
}

/** Book 8.11: a sheet from the bottom, radius 16, with its handle. Back or a touch above it calls [onDismiss]. */
@Composable
fun BottomSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        val c = Margem.colors
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let {
            it.setDimAmount(if (c.isDark) 0.6f else 0.45f)
            it.setGravity(android.view.Gravity.BOTTOM)
        }
        val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        Column(
            modifier.fillMaxWidth().background(c.surface, shape).verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 30.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp).size(32.dp, 4.dp).background(c.ruleStrong, RoundedCornerShape(2.dp)))
            content()
        }
    }
}

// ---- 8.8 Dialog and snackbar ----

/**
 * Book §8.8: only for what can't be undone (deleting a budget or a tag); everything else uses Desfazer.
 * Folha, radius 8, shadow, title in Newsreader 22, actions as text buttons; [destructive] in lacre.
 */
@Composable
fun MargemDialog(
    title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true, tag: String = "",
    dismiss: String = "Cancelar",
) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        val c = Margem.colors
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(if (c.isDark) 0.6f else 0.45f)
        DialogCard(title, text, confirm, onConfirm, onDismiss, destructive, tag = tag, dismiss = dismiss)
    }
}

/** The dialog's sheet, also drawn inline by the catalog. [tag]: the sheet, "$tag.confirm", "$tag.cancel" (D35). */
@Composable
fun DialogCard(
    title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true,
    modifier: Modifier = Modifier, tag: String = "", dismiss: String = "Cancelar",
) {
    val c = Margem.colors
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.testTag(tag).semantics(mergeDescendants = true) {}.padding(horizontal = 32.dp).widthIn(max = 340.dp).fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = c.shadow, spotColor = c.shadow).background(c.surface, shape)
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
    ) {
        Text(title, serif(22, 500, 28))
        Text(text, sans(15f, 400, 22.5f), Modifier.padding(top = 8.dp), c.inkSecondary)
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            Button(dismiss, onDismiss, Modifier.testTag("$tag.cancel"), kind = ButtonKind.TEXT)
            Button(confirm, onConfirm, Modifier.testTag("$tag.confirm"), kind = ButtonKind.TEXT, color = if (destructive) c.overrun else Color.Unspecified)
        }
    }
}

/** Tags of the app-wide snackbar (D35): the text of the current snack, and its action ("Desfazer"). Absent when none is shown. */
object SnackTags {
    const val TEXT = "snack.text"
    const val ACTION = "snack.action"
}

/** One snackbar: text and, when undoable, an action (book §8.8). */
class Snack(val text: String, val action: String = "", val onAction: suspend () -> Unit = {})

/**
 * Holds the snackbar on screen; [show] replaces the current one. Actions run on [scope], the App's: a
 * Desfazer still works after the screen that showed it is gone.
 */
class SnackHost(val scope: CoroutineScope) {
    var current by mutableStateOf<Snack?>(null)
    fun show(snack: Snack) {
        current = snack
    }
}

/** Book §8.8: inverted ink, radius 6, up to two lines, no icon. Stays 6 s (book §7.3). */
@Composable
fun Snackbar(text: String, modifier: Modifier = Modifier, action: String = "", onAction: () -> Unit = {}) {
    val c = Margem.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).shadow(14.dp, shape, ambientColor = c.shadow, spotColor = c.shadow).background(c.ink, shape)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, sans(15f, 400, 19.5f), Modifier.weight(1f).testTag(SnackTags.TEXT).padding(vertical = 8.dp), c.paper, maxLines = 2)
        if (action.isNotEmpty()) Box(
            Modifier.heightIn(min = 48.dp).testTag(SnackTags.ACTION).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(action, sans(15f, 700, 20f, 0.04f), color = c.snackAction) }
    }
}

/** Shows [host]'s snack for 6 s, fading in and out (book §6.4, `brief`). */
@Composable
fun SnackbarHost(host: SnackHost, modifier: Modifier = Modifier) {
    val snack = host.current
    LaunchedEffect(snack) {
        if (snack == null) return@LaunchedEffect
        delay(6_000)
        if (host.current === snack) host.current = null
    }
    var last by remember { mutableStateOf<Snack?>(null) }
    if (snack != null) last = snack
    AnimatedVisibility(snack != null, modifier, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
        val s = last ?: return@AnimatedVisibility
        Snackbar(s.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), s.action) {
            host.current = null
            host.scope.launch { s.onAction() }
        }
    }
}

// ---- 8.9 Date and time ----

/**
 * Tags of [DateTimeDialog] (D35, D36). Book 8.9: the month grid with the week starting on
 * Settings.weekStart, today in argila, future days disabled, the time typed like a value.
 */
object DateTimeTags {
    /** The dialog. Back press or a touch outside closes it like [CANCEL]. */
    const val DIALOG = "dateTime"

    /** The chosen date: "quinta, 8 de outubro" (dateLong). Follows the day picked in the grid. */
    const val TITLE = "dateTime.title"

    /** The month shown: "outubro de 2026" (monthTitle). Opens on the chosen date's month. */
    const val MONTH = "dateTime.month"

    /** One month back (no limit) and one forward; forward is disabled on today's month (enabled always with `future`, book 8.9). */
    const val PREV = "dateTime.prev"
    const val NEXT = "dateTime.next"

    /** The weekday header: "seg", "ter", … (weekdayShort), seven texts starting on Settings.weekStart. */
    const val WEEKDAYS = "dateTime.weekdays"

    /**
     * Prefix: DAY + date ("dateTime.day.2026-10-08"): every day of the month shown. Disabled after today,
     * unless the dialog takes `future` (Novo lançamento only: a day ahead becomes a previsto, spec §7.3);
     * `selected` on the chosen date. Click chooses it.
     */
    const val DAY = "dateTime.day."

    /**
     * Hora input, starting with the chosen time. Digits fill from the right, like cents in a money input:
     * "1430" = 14:30, "905" = 09:05; how it displays is not part of the contract.
     */
    const val TIME = "dateTime.time"

    /** After [DONE] with an impossible time (hour > 23 or minute > 59): "A hora vai de 00:00 a 23:59." Absent otherwise. */
    const val TIME_ERROR = "dateTime.time.error"

    /** "Pronto": with a valid time, calls onDone(date, time) and closes; otherwise shows [TIME_ERROR] and stays. */
    const val DONE = "dateTime.done"

    /** "Cancelar": closes without calling onDone. */
    const val CANCEL = "dateTime.cancel"
}

/**
 * Book 8.9: date and time of a transaction. Opens on [date] and [time]; days after [today] can't be chosen
 * unless [future] (the manual form, where a day ahead makes a previsto).
 * [weekStart] orders the grid (Settings.weekStart). [onDone] receives the choice; [onDismiss] closes it
 * either way. Tags: [DateTimeTags].
 */
@Composable
fun DateTimeDialog(
    date: LocalDate, time: LocalTime, today: LocalDate, weekStart: DayOfWeek,
    onDone: (LocalDate, LocalTime) -> Unit, onDismiss: () -> Unit, future: Boolean = false,
) {
    val c = Margem.colors
    var chosen by rememberSaveable { mutableStateOf(date.toString()) }
    var shown by rememberSaveable { mutableStateOf(date.yearMonth.toString()) }
    var digits by rememberSaveable { mutableStateOf((time.hour * 100 + time.minute).toString().trimStart('0')) }
    var error by rememberSaveable { mutableStateOf(false) }
    val day = LocalDate.parse(chosen)
    val month = YearMonth.parse(shown)
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(if (c.isDark) 0.6f else 0.45f)
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.testTag(DateTimeTags.DIALOG).padding(horizontal = 12.dp).widthIn(max = 336.dp).fillMaxWidth()
                .shadow(14.dp, shape, ambientColor = c.shadow, spotColor = c.shadow).background(c.surface, shape)
                .verticalScroll(rememberScrollState()).padding(top = 22.dp, bottom = 12.dp),
        ) {
            Text("DATA E HORA", sans(11f, 600, 14f, 0.12f), Modifier.padding(horizontal = 24.dp), c.inkTertiary)
            Text(dateLong(day), serif(22, 500, 28), Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 6.dp).testTag(DateTimeTags.TITLE))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(R.drawable.i_voltar, { shown = month.minusMonth().toString() }, Modifier.testTag(DateTimeTags.PREV))
                Text(monthTitle(month), serif(17, 500, 22), Modifier.weight(1f).testTag(DateTimeTags.MONTH), align = TextAlign.Center)
                val canNext = future || month < today.yearMonth
                IconButton(R.drawable.i_avancar, { shown = month.plusMonth().toString() }, Modifier.testTag(DateTimeTags.NEXT), dim = !canNext, enabled = canNext)
            }
            val order = List(7) { DayOfWeek((weekStart.isoDayNumber - 1 + it) % 7 + 1) }
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { testTag = DateTimeTags.WEEKDAYS; this[SemanticsProperties.Text] = order.map { AnnotatedString(weekdayShort(it)) } }) {
                for (d in order) Text(weekdayShort(d).uppercase(), sans(10.5f, 600, 28f, 0.06f), Modifier.weight(1f), c.inkTertiary, align = TextAlign.Center)
            }
            // The month's grid: blanks before the 1st, then one cell per day, 7 per row.
            val first = month.onDay(1)
            val blanks = (first.dayOfWeek.isoDayNumber - weekStart.isoDayNumber).mod(7)
            val cells = List(blanks) { 0 } + (1..month.numberOfDays).toList()
            for (row in cells.chunked(7)) Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val n = row.getOrElse(i) { 0 }
                    if (n == 0) {
                        Spacer(Modifier.weight(1f).height(48.dp))
                        continue
                    }
                    val d = month.onDay(n)
                    val on = d == day
                    val shut = !future && d > today
                    Box(
                        Modifier.weight(1f).height(48.dp).testTag(DateTimeTags.DAY + d).semantics { selected = on }
                            .clickable(enabled = !shut, role = Role.RadioButton) { chosen = d.toString() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(40.dp).background(if (on) c.ink else Color.Transparent, RoundedCornerShape(3.dp)).drawBehind {
                                if (d == today) drawRect(c.brand, Offset(0f, size.height - (if (on) 3 else 2).dp.toPx()), Size(size.width, (if (on) 3 else 2).dp.toPx()))
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                n.toString(), serif(15, if (d == today) 600 else if (shut) 400 else 500, 20, tabular = true),
                                color = when {
                                    on -> c.paper
                                    d == today -> c.brand
                                    shut -> c.inkTertiary
                                    else -> c.ink
                                },
                            )
                        }
                    }
                }
            }
            TimeField(digits, { digits = it; error = false }, error, Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                Button("Cancelar", onDismiss, Modifier.testTag(DateTimeTags.CANCEL), kind = ButtonKind.TEXT)
                Button("Pronto", {
                    val n = digits.ifEmpty { "0" }.toInt()
                    if (n / 100 > 23 || n % 100 > 59) error = true
                    else { onDone(day, LocalTime(n / 100, n % 100)); onDismiss() }
                }, Modifier.testTag(DateTimeTags.DONE), kind = ButtonKind.TEXT)
            }
        }
    }
}

/** "HH:MM" filled from the right (book 8.9): typed digits are the minutes first, like cents. */
private class TimeTransformation(val dim: Color) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val d = text.text.padStart(4, '0')
        val pad = 4 - text.text.length
        val shown = buildAnnotatedString {
            for ((i, ch) in "${d.take(2)}:${d.drop(2)}".withIndex()) {
                val dimmed = (if (i < 2) i else i - 1) < pad
                if (dimmed) withStyle(SpanStyle(color = dim)) { append(ch) } else append(ch)
            }
        }
        return withTypedTail(shown, text.text.length)
    }
}

@Composable
private fun TimeField(digits: String, onChange: (String) -> Unit, error: Boolean, modifier: Modifier) {
    val c = Margem.colors
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    // Keeps the keyboard's composition, like MoneyField.
    var typed by remember { mutableStateOf(TextFieldValue(digits, androidx.compose.ui.text.TextRange(digits.length))) }
    Column(modifier.padding(top = 8.dp, bottom = 6.dp)) {
        FieldLabel("Hora", if (error) c.overrun else if (focused) c.ink else c.inkTertiary)
        BasicTextField(
            if (typed.text == digits) typed else TextFieldValue(digits, androidx.compose.ui.text.TextRange(digits.length)),
            { v ->
                val clean = v.text.filter(Char::isDigit).trimStart('0').take(4)
                typed = if (clean == v.text) v else TextFieldValue(clean, androidx.compose.ui.text.TextRange(clean.length))
                onChange(clean)
            },
            Modifier.fillMaxWidth().testTag(DateTimeTags.TIME),
            textStyle = serif(19, 400, 24, tabular = true).copy(color = c.ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, interactionSource = interaction, cursorBrush = SolidColor(c.info),
            visualTransformation = TimeTransformation(c.inkTertiary),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).fieldLine(focused, error, true, c), contentAlignment = Alignment.CenterStart) { inner() }
            },
        )
        FieldHint("", if (error) "A hora vai de 00:00 a 23:59." else "", DateTimeTags.TIME)
    }
}

/** Spacer shorthand for vertical rhythm. */
@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HGap(width: Dp) = Spacer(Modifier.width(width))
