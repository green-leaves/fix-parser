package io.hermes.fix.plugin

import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Element

class LineNumberGutter(private val textArea: JTextArea) : JPanel() {
    init {
        font = textArea.font
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        background = JBUI.CurrentTheme.EditorTabs.background()
        border = JBUI.Borders.empty(0, 8, 0, 8)

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = repaint()
            override fun removeUpdate(e: DocumentEvent) = repaint()
            override fun changedUpdate(e: DocumentEvent) = repaint()
        })
    }

    override fun getPreferredSize(): Dimension {
        val lineCount = textArea.lineCount.coerceAtLeast(1)
        val digits = lineCount.toString().length.coerceAtLeast(2)
        val charWidth = getFontMetrics(font).charWidth('0')
        val width = charWidth * digits + insets.left + insets.right
        return Dimension(width, textArea.preferredSize.height)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = font
        g2.color = foreground

        val fm = g2.fontMetrics
        val root: Element = textArea.document.defaultRootElement
        val clip = g.clipBounds

        val startOffset = textArea.viewToModel2D(Point(0, clip.y))
        val endOffset = textArea.viewToModel2D(Point(0, clip.y + clip.height))
        val startLine = root.getElementIndex(startOffset)
        val endLine = root.getElementIndex(endOffset)

        for (line in startLine..endLine) {
            val element = root.getElement(line)
            val y = try {
                textArea.modelToView2D(element.startOffset)?.y?.toInt() ?: continue
            } catch (_: Exception) { continue }

            val lineNum = (line + 1).toString()
            val x = insets.left + (preferredSize.width - insets.left - insets.right - fm.stringWidth(lineNum))
            g2.drawString(lineNum, x, y + fm.ascent)
        }
    }
}
