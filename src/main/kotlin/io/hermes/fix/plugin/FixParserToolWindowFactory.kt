package io.hermes.fix.plugin

import com.intellij.ide.plugins.newui.UpdateButton
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class FixParserToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = FixParserPanel()
        toolWindow.contentManager.addContent(
            toolWindow.contentManager.factory.createContent(content.getComponent(), "", false)
        )
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

class FixParserPanel {

    // ============================================================
    // Color Palette - Theme-aware constants
    // ============================================================
    companion object {
        // Primary accent - Theme-aware (Indigo)
        val ACCENT_TEAL = JBColor(
            Color(51, 72, 107),    // Light mode
            Color(80, 130, 180)    // Dark mode
        )
        val ACCENT_BLUE = JBColor(
            Color(25, 118, 210),    // Light mode
            Color(25, 118, 210)    // Dark mode
        )

        // Message type colors (semantic - same in both modes)
        val ORDER_GREEN = Color(34, 139, 34)
        val ORDER_BG = Color(232, 245, 233)
        val EXEC_BLUE = Color(65, 105, 225)
        val EXEC_BG = Color(227, 242, 253)
        val LOGON_AMBER = Color(200, 130, 0)
        val LOGON_BG = Color(255, 248, 230)
        val REJECT_RED = Color(180, 40, 40)
        val REJECT_BG = Color(255, 240, 240)

        // Theme-aware stripe colors
        fun stripeColor() = JBColor(
            Color(80, 187, 234),  // Light mode
            Color(36, 36, 36)      // Dark mode
        )
    }

    // ============================================================
    // AnAction classes for IntelliJ-style buttons
    // ============================================================
    private inner class ParseAction : AnAction("Parse") {
        override fun actionPerformed(e: AnActionEvent) {
            performParse()
        }
    }

    private inner class ClearAction : AnAction("Clear") {
        override fun actionPerformed(e: AnActionEvent) {
            clearAll()
        }
    }

    private val largeMonoFont = Font("Fira Code", Font.PLAIN, 14)
    private val boldFont = JBUI.Fonts.label().deriveFont(Font.BOLD, 14f)
    private val normalFont = JBUI.Fonts.label().deriveFont(14f)

    /** FIX version dropdown */
    private val fixVersionCombo = ComboBox(FixSpecVersion.entries.map { it.displayName }.toTypedArray()).apply {
        font = normalFont
        addActionListener { reloadDictionary() }
    }

    /** Current loaded dictionary */
    private var currentDictionary: uk.co.real_logic.artio.dictionary.ir.Dictionary? = null

    /** Map of FIX spec version to resource path */
    enum class FixSpecVersion(val displayName: String, val resourcePath: String) {
        FIX_4_4("FIX 4.4", "/spec/FIX44.xml"),
        FIX_4_2("FIX 4.2", "/spec/FIX42.xml"),
        FIX_4_1("FIX 4.1", "/spec/FIX41.xml"),
        FIX_4_0("FIX 4.0", "/spec/FIX40.xml"),
        FIX_5_0("FIX 5.0", "/spec/FIX50.xml"),
        FIX_5_0_SP1("FIX 5.0 SP1", "/spec/FIX50SP1.xml"),
        FIX_5_0_SP2("FIX 5.0 SP2", "/spec/FIX50SP2.xml")
    }

    private fun reloadDictionary() {
        val selectedVersion = fixVersionCombo.selectedItem as? String ?: return
        val specVersion = FixSpecVersion.entries.find { it.displayName == selectedVersion }

        currentDictionary = specVersion?.let { version ->
            try {
                FixParserToolWindowFactory::class.java.getResourceAsStream(version.resourcePath)?.let { stream ->
                    FixMessageParser.loadDictionary(stream)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private val inputArea = JTextArea(10, 80).apply {
        font = largeMonoFont
        margin = JBUI.insets(10)
        background = JBUI.CurrentTheme.EditorTabs.background()

        // Auto-parse on paste
        transferHandler = object : TransferHandler("text") {
            override fun importData(transferred: TransferHandler.TransferSupport): Boolean {
                val result = super.importData(transferred)
                if (result) {
                    SwingUtilities.invokeLater {
                        if (text.isNotBlank()) {
                            performParse()
                        }
                    }
                }
                return result
            }
        }
    }

    private val summaryModel = object : DefaultTableModel(arrayOf("Time", "Sender", "Target", "Message Type"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }

    private val summaryTable = JBTable(summaryModel).apply {
        setShowGrid(false)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = 28
        font = normalFont
        tableHeader.font = boldFont
        isStriped = true

        // Color row renderer - using constants
        setDefaultRenderer(Object::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (!isSelected) {
                    val msgType = table.getValueAt(row, 3) as? String ?: ""
                    c.background = when {
                        msgType.contains("Order") -> ORDER_BG
                        msgType.contains("Execution") -> EXEC_BG
                        msgType.contains("Logon") || msgType.contains("Logout") -> LOGON_BG
                        msgType.contains("Reject") -> REJECT_BG
                        else -> table.background
                    }
                    c.foreground = table.foreground
                } else {
                    c.background = ACCENT_TEAL
                    c.foreground = Color.WHITE
                }
                return c
            }
        })
    }

    private val detailsModel = object : DefaultTableModel(arrayOf("Tag", "Name", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }

    private val detailsTable = JBTable(detailsModel).apply {
        // Clean look - no grid, no borders
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        showHorizontalLines = false
        showVerticalLines = false
        border = null
        font = largeMonoFont
        rowHeight = 28
        tableHeader.font = boldFont
        emptyText.text = "No data available"
        setRowSelectionAllowed(true)
        setColumnSelectionAllowed(false)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        // Theme-aware stripe color using constants
        val stripeColor = stripeColor()

        for (colIdx in 0 until columnCount) {
            val column = getColumnModel().getColumn(colIdx)
            val isTagColumnFinal = colIdx == 0

            column.cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, col: Int
                ): Component {
                    val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
                    if (!isSelected) {
                        c.background = if (row % 2 == 0) table.background else stripeColor
                        c.foreground = if (isTagColumnFinal) ACCENT_TEAL else table.foreground
                        font = if (isTagColumnFinal) {
                            largeMonoFont.deriveFont(Font.BOLD)
                        } else {
                            largeMonoFont
                        }
                    } else {
                        c.background = ACCENT_TEAL
                        c.foreground = Color.WHITE
                    }
                    (c as? JLabel)?.horizontalAlignment = JLabel.LEFT
                    return c
                }
            }
        }

        // Start with columns filling all space (empty state)
        autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
    }

    private val statusLabel = JBLabel("This data will not leave your computer").apply {
        icon = com.intellij.icons.AllIcons.General.BalloonInformation
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = normalFont
    }

    private var parsedResult: ParsedResult? = null

    // Start Here?
    init {
        summaryTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateDetails()
            }
        }
        // Load default dictionary on startup
        reloadDictionary()
    }

    fun getComponent(): JComponent {
        val root = JPanel(BorderLayout())

        // Top: Input tabs
        val topTabs = JBTabbedPane().apply {
            font = boldFont
        }
        topTabs.addTab("Text", JBScrollPane(inputArea).apply {
            border = SideBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), SideBorder.BOTTOM)
        })
        topTabs.addTab("Local file", JPanel())

        // Bottom split: Summary & Details
        val splitter = OnePixelSplitter(false, 0.4f)

        // Left part: Messages list
        val leftPanel = JPanel(BorderLayout())
        val leftHeader = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = JBUI.Borders.empty(5, 10)
            add(JBLabel("Messages").apply { font = boldFont })
            add(JBLabel("0").apply {
                font = normalFont
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
            add(JBLabel("Filters").apply { font = normalFont; foreground = Color(25, 118, 210) })
        }
        leftPanel.add(leftHeader, BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(summaryTable), BorderLayout.CENTER)

        // Right part: Message details
        val rightPanel = JPanel(BorderLayout())
        val rightTabs = JBTabbedPane().apply { font = boldFont }
        rightTabs.addTab("Message", JBScrollPane(detailsTable))
        rightTabs.addTab("Fields", JPanel())
        rightTabs.addTab("Raw", JPanel())
        rightTabs.addTab("Issues", JPanel())
        rightPanel.add(rightTabs, BorderLayout.CENTER)

        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel

        // Layout assembly
        val mainContent = OnePixelSplitter(true, 0.4f)
        mainContent.firstComponent = topTabs

        val bottomWrapper = JPanel(BorderLayout())
        val actionBar = JPanel().apply {
        layout = FlowLayout(FlowLayout.LEFT)
            border = JBUI.Borders.empty(5)
            add(JBLabel("FIX version:").apply { font = normalFont })
            add(Box.createRigidArea(Dimension(5, 0)))
            add(fixVersionCombo)
            add(Box.createRigidArea(Dimension(20, 0)))
            add(statusLabel)
            add(Box.createRigidArea(Dimension(20, 0)))
            add(UpdateButton().apply {
                text = "Parse"
                putClientProperty("JButton.buttonType", "textured")
                addActionListener { performParse() }
            })
            add(JButton("Clear").apply {
                font = boldFont
                putClientProperty("JButton.buttonType", "textured")
                addActionListener { clearAll() }
            })
        }
        bottomWrapper.add(actionBar, BorderLayout.NORTH)
        bottomWrapper.add(splitter, BorderLayout.CENTER)

        mainContent.secondComponent = bottomWrapper

        root.add(mainContent, BorderLayout.CENTER)
        return root
    }

    private fun performParse() {
        val text = inputArea.text
        if (text.isBlank()) return

        try {
            // Ensure dictionary is loaded
            if (currentDictionary == null) {
                reloadDictionary()
            }
            parsedResult = FixMessageParser.parseMessages(text, dictionary = currentDictionary)
            parsedResult?.let { result ->
                summaryModel.rowCount = 0
                result.messages.forEach { msg ->
                    summaryModel.addRow(arrayOf(msg.time, msg.sender, msg.target, msg.msgType))
                }

                // Update count in header
                getLeftHeaderCountLabel()?.text = result.messages.size.toString()

                if (summaryTable.rowCount > 0) {
                    summaryTable.setRowSelectionInterval(0, 0)
                }
            }
        } catch (e: Exception) {
            // handle error
        }
    }

    private fun updateDetails() {
        val selected = summaryTable.selectedRow
        if (selected < 0) {
            detailsModel.rowCount = 0
            detailsTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            return
        }

        parsedResult?.messages?.getOrNull(selected)?.let { msg ->
            detailsModel.rowCount = 0
            msg.tags.forEach { tag ->
                detailsModel.addRow(arrayOf(tag.tagId, tag.tagName, tag.value))
            }
            // Resize columns to fit content
            resizeColumnsToFit()
        }
    }

    private fun resizeColumnsToFit() {
        // Switch to fixed-width mode so columns fit content
        detailsTable.autoResizeMode = JTable.AUTO_RESIZE_OFF

        val columnCount = detailsTable.columnCount
        val lastColIndex = columnCount - 1
        var contentWidth = 0

        // Calculate width for first (columnCount - 1) columns
        for (col in 0 until lastColIndex) {
            val column = detailsTable.columnModel.getColumn(col)
            var maxWidth = column.headerRenderer?.getTableCellRendererComponent(
                detailsTable, column.headerValue, false, false, 0, col
            )?.preferredSize?.width ?: 0

            for (row in 0 until detailsTable.rowCount) {
                val cellRenderer = detailsTable.getCellRenderer(row, col)
                val cellValue = detailsTable.getValueAt(row, col)
                val rendererComponent = cellRenderer.getTableCellRendererComponent(
                    detailsTable, cellValue, false, false, row, col
                )
                maxWidth = maxOf(maxWidth, rendererComponent.preferredSize.width)
            }
            column.preferredWidth = maxWidth + 20
            contentWidth += column.preferredWidth
        }

        // Last column fills remaining width
        val tableWidth = detailsTable.parent?.let { (it as? JViewport)?.width } ?: detailsTable.width
        val remainingWidth = maxOf(tableWidth - contentWidth, 200)
        detailsTable.columnModel.getColumn(lastColIndex).preferredWidth = remainingWidth
    }

    private fun clearAll() {
        inputArea.text = ""
        summaryModel.rowCount = 0
        detailsModel.rowCount = 0
        detailsTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        parsedResult = null

        // Reset count in header
        getLeftHeaderCountLabel()?.text = "0"
    }

    // Reset count in header
    private fun getLeftHeaderCountLabel(): JBLabel? {
        // Safe navigation to count label
        return try {
            (((summaryTable.parent as? JViewport)?.parent as? JScrollPane)?.parent as? JPanel)?.let { leftPanel ->
                (leftPanel.components[0] as? JPanel)?.getComponent(1) as? JBLabel
            }
        } catch (e: Exception) { null }
    }
}
