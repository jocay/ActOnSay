package com.voice.actonsay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Item(val marker: String, val indent: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val TABLE_DIVIDER = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

private fun tableCells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

/** 只覆盖模型回复里常见的 Markdown 子集；流式生成中未闭合的标记按原文显示。 */
private fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    val lines = source.replace("\r\n", "\n").split('\n')
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) { code.append(lines[index]).append('\n'); index++ }
                blocks += MdBlock.Code(code.toString().trimEnd('\n'))
            }
            trimmed.isEmpty() -> flush()
            RULE.matches(line) -> { flush(); blocks += MdBlock.Rule }
            HEADING.matches(trimmed) -> {
                flush()
                val match = HEADING.find(trimmed)!!
                blocks += MdBlock.Heading(match.groupValues[1].length, match.groupValues[2].trimEnd('#', ' '))
            }
            trimmed.startsWith("|") && index + 1 < lines.size && TABLE_DIVIDER.matches(lines[index + 1]) -> {
                flush()
                val rows = mutableListOf(tableCells(line))
                index += 2
                while (index < lines.size && lines[index].trim().startsWith("|")) { rows += tableCells(lines[index]); index++ }
                blocks += MdBlock.Table(rows)
                continue
            }
            BULLET.matches(line) -> {
                flush()
                val match = BULLET.find(line)!!
                blocks += MdBlock.Item("•", match.groupValues[1].length / 2, match.groupValues[2])
            }
            ORDERED.matches(line) -> {
                flush()
                val match = ORDERED.find(line)!!
                blocks += MdBlock.Item(match.groupValues[2] + ".", match.groupValues[1].length / 2, match.groupValues[3])
            }
            trimmed.startsWith(">") -> { flush(); blocks += MdBlock.Quote(trimmed.trimStart('>', ' ')) }
            else -> { if (paragraph.isNotEmpty()) paragraph.append('\n'); paragraph.append(trimmed) }
        }
        index++
    }
    flush()
    return blocks
}

private fun inlineMarkdown(text: String, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val mark = text.substring(i, i + 2)
            val end = text.indexOf(mark, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inlineMarkdown(text.substring(i + 2, end), codeBackground, linkColor)) }
                i = end + 2
                continue
            }
        } else if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inlineMarkdown(text.substring(i + 2, end), codeBackground, linkColor)) }
                i = end + 2
                continue
            }
        } else if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        } else if (c == '*' && i + 1 < text.length && !text[i + 1].isWhitespace()) {
            val end = text.indexOf('*', i + 1)
            if (end > i + 1 && !text[end - 1].isWhitespace()) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineMarkdown(text.substring(i + 1, end), codeBackground, linkColor)) }
                i = end + 1
                continue
            }
        } else if (c == '[') {
            val close = text.indexOf("](", i + 1)
            val end = if (close > i) text.indexOf(')', close + 2) else -1
            if (close > i + 1 && end > close + 2 && '\n' !in text.substring(i, end)) {
                val url = text.substring(close + 2, end).trim().substringBefore(' ')
                val label = text.substring(i + 1, close)
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                        append(inlineMarkdown(label, codeBackground, linkColor))
                    }
                } else append(inlineMarkdown(label, codeBackground, linkColor))
                i = end + 1
                continue
            }
        }
        append(c)
        i++
    }
}

/** 渲染助手回复中的 Markdown：标题、列表、引用、代码、表格、分隔线，以及加粗、斜体、行内代码和链接。 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val scheme = MaterialTheme.colorScheme
    val codeBackground = scheme.onSurface.copy(alpha = 0.08f)
    val linkColor = scheme.primary
    @Composable
    fun inline(value: String) = remember(value, codeBackground, linkColor) { inlineMarkdown(value, codeBackground, linkColor) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(inline(block.text), style = style, color = color)
                is MdBlock.Heading -> Text(
                    inline(block.text), color = color,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.copy(fontWeight = FontWeight.SemiBold)
                )
                is MdBlock.Item -> Row(Modifier.padding(start = (block.indent * 14).dp)) {
                    Text(block.marker, style = style, color = color.copy(alpha = 0.7f), modifier = Modifier.width(if (block.marker == "•") 16.dp else 26.dp))
                    Text(inline(block.text), style = style, color = color, modifier = Modifier.weight(1f))
                }
                is MdBlock.Quote -> Row {
                    Text("▎", style = style, color = scheme.outline)
                    Text(inline(block.text), style = style, color = color.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                }
                is MdBlock.Code -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(codeBackground)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                )
                is MdBlock.Table -> Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(codeBackground).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val columns = block.rows.maxOf { it.size }
                    block.rows.forEachIndexed { rowIndex, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (column in 0 until columns) {
                                Text(
                                    inline(row.getOrElse(column) { "" }),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal),
                                    color = color,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (rowIndex == 0 && block.rows.size > 1) HorizontalDivider(color = scheme.outlineVariant)
                    }
                }
                MdBlock.Rule -> HorizontalDivider(color = scheme.outlineVariant)
            }
        }
    }
}
