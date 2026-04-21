package io.hermes.fix.plugin

import com.intellij.ide.plugins.newui.UpdateButton
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
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
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.table.JBTable
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class FixParserToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = FixParserPanel()
        toolWindow.contentManager.addContent(
            toolWindow.contentManager.factory.createContent(content.getComponent(), "", false)
        )
    }
}

class FixParserPanel {

    // ============================================================
    // Color Palette - Theme-aware constants
    // ============================================================
    companion object {
        val ACCENT_BLUE = JBColor(
            Color(0xADD6FF),
            Color(0x214283)
        )

        // Theme-aware stripe colors
        fun stripeColor() = JBColor(
            Color(0xEDF3FB),
            Color(0x313335)
        )

        const val CUSTOM_DICTIONARY_LABEL = "Custom..."

        fun csvEscape(s: String) = "\"${s.replace("\"", "\"\"")}\""
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

    private val largeMonoFont = Font("Segoe UI", Font.PLAIN, 14)
    private val boldFont = JBUI.Fonts.label().deriveFont(Font.BOLD, 14f)
    private val normalFont = JBUI.Fonts.label().deriveFont(14f)

    /** Path to user-selected custom dictionary file */
    private var customDictionaryPath: java.io.File? = null

    /** Previous selection before "Custom..." was picked (for cancel fallback) */
    private var previousSelection: String? = null

    /** FIX version dropdown */
    private val fixVersionCombo: ComboBox<String> = ComboBox(
        (FixSpecVersion.entries.map { it.displayName } + CUSTOM_DICTIONARY_LABEL).toTypedArray()
    ).apply {
        font = normalFont
        addActionListener {
            val selected = selectedItem as? String ?: return@addActionListener
            if (selected == CUSTOM_DICTIONARY_LABEL) {
                browseForDictionary()
            } else {
                previousSelection = selected
                reloadDictionary()
            }
        }
    }

    /** Current loaded dictionary */
    private var currentDictionary: uk.co.real_logic.artio.dictionary.ir.Dictionary? = null

    /** Map of FIX spec version to resource path */
    enum class FixSpecVersion(val displayName: String, val resourcePath: String) {
        FIX_4_4("FIX 4.4", "/spec/FIX44.xml"),
        FIX_4_3("FIX 4.3", "/spec/FIX43.xml"),
        FIX_4_2("FIX 4.2", "/spec/FIX42.xml"),
        FIX_4_1("FIX 4.1", "/spec/FIX41.xml"),
        FIX_4_0("FIX 4.0", "/spec/FIX40.xml"),
        FIX_5_0("FIX 5.0", "/spec/FIX50.xml"),
        FIX_5_0_SP1("FIX 5.0 SP1", "/spec/FIX50SP1.xml"),
        FIX_5_0_SP2("FIX 5.0 SP2", "/spec/FIX50SP2.xml")
    }

    private fun browseForDictionary() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
            .withTitle("Select FIX Dictionary XML")
        val chooser = FileChooserFactory.getInstance().createPathChooser(descriptor, null, null)
        chooser.choose(null) { files ->
            val vFile = files.firstOrNull()
            if (vFile != null) {
                val file = java.io.File(vFile.path)
                customDictionaryPath = file
                // Replace "Custom..." label with the file name
                val customLabel = file.name
                fixVersionCombo.removeItemAt(fixVersionCombo.itemCount - 1)
                fixVersionCombo.addItem(customLabel)
                fixVersionCombo.addItem(CUSTOM_DICTIONARY_LABEL)
                fixVersionCombo.selectedItem = customLabel
                previousSelection = customLabel
                loadCustomDictionary(file)
            } else {
                // User cancelled — revert to previous selection
                fixVersionCombo.selectedItem = previousSelection ?: FixSpecVersion.entries.first().displayName
            }
        }
    }

    private fun loadCustomDictionary(file: java.io.File) {
        currentDictionary = try {
            file.inputStream().use { FixMessageParser.loadDictionary(it) }
        } catch (e: Exception) {
            statusLabel.text = "Failed to load: ${file.name}"
            null
        }
    }

    private fun browseForFixFile() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select FIX Message File")
        val chooser = FileChooserFactory.getInstance().createPathChooser(descriptor, null, null)
        chooser.choose(null) { files ->
            val vFile = files.firstOrNull() ?: return@choose
            val file = java.io.File(vFile.path)
            filePathField.text = file.absolutePath
            filePathField.foreground = JBUI.CurrentTheme.Label.foreground()
            try {
                val content = file.readText()
                inputArea.text = content
                performParse()
            } catch (e: Exception) {
                filePathField.text = "Failed to read: ${file.name}"
                filePathField.foreground = JBColor.RED
            }
        }
    }

    private fun reloadDictionary() {
        val selectedVersion = fixVersionCombo.selectedItem as? String ?: return
        if (selectedVersion == CUSTOM_DICTIONARY_LABEL) return

        // Check if it's a custom file name
        if (customDictionaryPath != null && selectedVersion == customDictionaryPath!!.name) {
            loadCustomDictionary(customDictionaryPath!!)
            return
        }

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
        lineWrap = true
        background = JBUI.CurrentTheme.EditorTabs.background()

        // Auto-parse when text is inserted (covers paste, drop, etc.)
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                SwingUtilities.invokeLater {
                    if (text.isNotBlank()) performParse()
                }
            }
            override fun removeUpdate(e: DocumentEvent) {}
            override fun changedUpdate(e: DocumentEvent) {}
        })
    }

    private val summaryModel = object : DefaultTableModel(arrayOf("Time", "Sender", "Target", "Message Type"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }

    private val sorter = TableRowSorter(summaryModel)

    private val messageCountLabel = JBLabel("").apply {
        font = normalFont
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
    }

    private fun createStripedRenderer(): DefaultTableCellRenderer {
        val stripe = stripeColor()
        return object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (!isSelected) {
                    c.background = if (row % 2 == 0) table.background else stripe
                } else {
                    c.background = ACCENT_BLUE
                }
                c.foreground = table.foreground
                return c
            }
        }
    }

    private fun JBTable.applyCommonStyle() {
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        showHorizontalLines = false
        showVerticalLines = false
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = 28
        font = largeMonoFont
        tableHeader.font = boldFont
        val renderer = createStripedRenderer()
        setDefaultRenderer(Object::class.java, renderer)
        for (colIdx in 0 until columnCount) {
            getColumnModel().getColumn(colIdx).cellRenderer = renderer
        }
    }

    private val summaryTable = object : JBTable(summaryModel) {
        override fun scrollRectToVisible(rect: Rectangle) {
            rect.x = 0
            rect.width = 0
            super.scrollRectToVisible(rect)
        }
    }.apply {
        applyCommonStyle()
    }

    private val detailsModel = object : DefaultTableModel(arrayOf("Tag", "Name", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }

    private val detailsTable = object : JBTable(detailsModel) {
        override fun scrollRectToVisible(rect: Rectangle) {
            rect.x = 0
            rect.width = 0
            super.scrollRectToVisible(rect)
        }
    }.apply {
        applyCommonStyle()
        border = null
        emptyText.text = "No data available"
        autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS

        // Ctrl+C copies the selected cell value
        val copyCell = object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                val row = selectedRow; val col = selectedColumn
                if (row >= 0 && col >= 0) {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(getValueAt(row, col)?.toString() ?: ""), null)
                }
            }
        }
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl C"), "copy-cell")
        actionMap.put("copy-cell", copyCell)

        // Right-click context menu
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                val row = rowAtPoint(e.point); val col = columnAtPoint(e.point)
                if (row < 0 || col < 0) return
                setRowSelectionInterval(row, row)
                setColumnSelectionInterval(col, col)
                JPopupMenu().apply {
                    add(JMenuItem("Copy Cell").apply {
                        addActionListener {
                            Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(StringSelection(getValueAt(row, col)?.toString() ?: ""), null)
                        }
                    })
                    add(JMenuItem("Copy Row").apply {
                        addActionListener {
                            val rowText = (0 until columnCount).joinToString("\t") { c ->
                                getValueAt(row, c)?.toString() ?: ""
                            }
                            Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(StringSelection(rowText), null)
                        }
                    })
                }.show(this@apply, e.x, e.y)
            }
        })
    }

    private val rawTextArea = JTextArea().apply {
        font = largeMonoFont
        isEditable = false
        lineWrap = true
        margin = JBUI.insets(10)
        background = JBUI.CurrentTheme.EditorTabs.background()
    }

    private val filePathField = JTextField("No file selected").apply {
        font = normalFont
        isEditable = false
        columns = 40
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = browseForFixFile()
        })
    }

    private val browseBar = JPanel(FlowLayout(FlowLayout.LEFT, 5, 3)).apply {
        add(JButton("Browse...").apply {
            font = boldFont
            putClientProperty("JButton.buttonType", "textured")
            addActionListener { browseForFixFile() }
        })
        add(filePathField)
    }

    private lateinit var rightTabs: JBTabbedPane

    private val statusLabel = JBLabel("This data will not leave your computer").apply {
        icon = com.intellij.icons.AllIcons.General.BalloonInformation
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = normalFont
    }

    private var parsedResult: ParsedResult? = null

    private var regexEnabled = false
    private var negateEnabled = false

    private val defaultFilterBg: Color by lazy { filterField.background }

    private val filterField = ExtendableTextField().apply {
        font = normalFont
        emptyText.text = "Filter messages..."

        // Left: magnifying glass (static)
        addExtension(object : ExtendableTextComponent.Extension {
            override fun getIcon(hovered: Boolean) = AllIcons.Actions.Search
            override fun isIconBeforeText() = true
            override fun getActionOnClick() = null
        })

        // Right: regex toggle (.*)
        addExtension(object : ExtendableTextComponent.Extension {
            override fun getIcon(hovered: Boolean) =
                if (regexEnabled) AllIcons.Actions.Regex
                else IconLoader.getDisabledIcon(AllIcons.Actions.Regex)
            override fun isIconBeforeText() = false
            override fun getActionOnClick() = Runnable {
                regexEnabled = !regexEnabled
                applyFilter()
                this@apply.repaint()
            }
        })

        // Right: negate toggle (!)
        addExtension(object : ExtendableTextComponent.Extension {
            override fun getIcon(hovered: Boolean) =
                if (negateEnabled) AllIcons.General.ExclMark
                else IconLoader.getDisabledIcon(AllIcons.General.ExclMark)
            override fun isIconBeforeText() = false
            override fun getActionOnClick() = Runnable {
                negateEnabled = !negateEnabled
                applyFilter()
                this@apply.repaint()
            }
        })

        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) {}
        })
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ESCAPE"), "clear-filter")
        actionMap.put("clear-filter", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) { text = "" }
        })
    }

    // Start Here?
    init {
        summaryTable.rowSorter = sorter
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

        // Top: Input panel
        val inputPanel = JPanel(BorderLayout()).apply {
            add(browseBar, BorderLayout.NORTH)
            add(JBScrollPane(inputArea).apply {
                setRowHeaderView(LineNumberGutter(inputArea))
                border = SideBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), SideBorder.BOTTOM)
            }, BorderLayout.CENTER)
        }

        // Bottom split: Summary & Details
        val splitter = OnePixelSplitter(false, 0.4f)

        // Left part: Messages list
        val leftPanel = JPanel(BorderLayout())
        val leftHeader = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, 10)
            add(JBLabel("Messages").apply {
                font = boldFont
                alignmentY = Component.CENTER_ALIGNMENT
            })
            add(Box.createRigidArea(Dimension(6, 0)))
            add(messageCountLabel.apply { alignmentY = Component.CENTER_ALIGNMENT })
            add(Box.createRigidArea(Dimension(8, 0)))
            add(filterField.apply {
                alignmentY = Component.CENTER_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        }
        leftPanel.add(leftHeader, BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(summaryTable), BorderLayout.CENTER)

        // Right part: Message details
        val rightPanel = JPanel(BorderLayout())
        rightTabs = JBTabbedPane().apply { font = boldFont }
        val copyCsvButton = JButton("Copy CSV").apply {
            font = boldFont
            putClientProperty("JButton.buttonType", "textured")
            addActionListener {
                if (detailsModel.rowCount == 0) return@addActionListener
                val csv = buildString {
                    for (row in 0 until detailsModel.rowCount) {
                        append(csvEscape(detailsModel.getValueAt(row, 0)?.toString() ?: ""))
                        append(",")
                        append(csvEscape(detailsModel.getValueAt(row, 1)?.toString() ?: ""))
                        append(",")
                        append(csvEscape(detailsModel.getValueAt(row, 2)?.toString() ?: ""))
                        append("\n")
                    }
                }
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(csv), null)
                text = "Copied!"
                isEnabled = false
                Timer(500) { text = "Copy CSV"; isEnabled = true }.apply { isRepeats = false; start() }
            }
        }
        val copyTableButton = JButton("Copy").apply {
            font = boldFont
            putClientProperty("JButton.buttonType", "textured")
            addActionListener {
                if (detailsModel.rowCount == 0) return@addActionListener
                val tsv = buildString {
                    append("Tag\tName\tValue\n")
                    for (row in 0 until detailsModel.rowCount) {
                        append(detailsModel.getValueAt(row, 0)?.toString() ?: "")
                        append("\t")
                        append(detailsModel.getValueAt(row, 1)?.toString() ?: "")
                        append("\t")
                        append(detailsModel.getValueAt(row, 2)?.toString() ?: "")
                        append("\n")
                    }
                }
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(tsv), null)
                text = "Copied!"
                isEnabled = false
                Timer(500) { text = "Copy"; isEnabled = true }.apply { isRepeats = false; start() }
            }
        }
        val fieldsPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(detailsTable), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 5, 2)).apply {
                add(copyTableButton)
                add(copyCsvButton)
            }, BorderLayout.SOUTH)
        }
        rightTabs.addTab("Fields", fieldsPanel)
        rightTabs.addTab("Raw", JBScrollPane(rawTextArea))
        rightPanel.add(rightTabs, BorderLayout.CENTER)

        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel

        // Layout assembly
        val mainContent = OnePixelSplitter(true, 0.4f)
        mainContent.firstComponent = inputPanel

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

                summaryTable.resizeColumnsToFit()
                applyFilter()

                if (summaryTable.rowCount > 0) {
                    summaryTable.setRowSelectionInterval(0, 0)
                }
            }
        } catch (e: Exception) {
            // handle error
        }
    }

    private fun updateDetails() {
        val viewRow = summaryTable.selectedRow
        if (viewRow < 0) {
            detailsModel.rowCount = 0
            detailsTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            rawTextArea.text = ""
            rightTabs.setTitleAt(0, "Fields")
            return
        }

        val selected = summaryTable.convertRowIndexToModel(viewRow)
        parsedResult?.messages?.getOrNull(selected)?.let { msg ->
            detailsModel.rowCount = 0
            msg.tags.forEach { tag ->
                detailsModel.addRow(arrayOf(tag.tagId, tag.tagName, tag.value))
            }
            detailsTable.resizeColumnsToFit()
            rawTextArea.text = msg.rawMessage
            rawTextArea.caretPosition = 0
            rightTabs.setTitleAt(0, "Fields (${msg.tags.size})")
        }
    }

    private fun JBTable.resizeColumnsToFit() {
        autoResizeMode = JTable.AUTO_RESIZE_OFF

        val lastColIndex = columnCount - 1
        var contentWidth = 0

        for (col in 0 until lastColIndex) {
            val column = columnModel.getColumn(col)
            var maxWidth = column.headerRenderer?.getTableCellRendererComponent(
                this, column.headerValue, false, false, 0, col
            )?.preferredSize?.width ?: 0

            for (row in 0 until rowCount) {
                val rendererComponent = getCellRenderer(row, col).getTableCellRendererComponent(
                    this, getValueAt(row, col), false, false, row, col
                )
                maxWidth = maxOf(maxWidth, rendererComponent.preferredSize.width)
            }
            column.preferredWidth = maxWidth + 20
            contentWidth += column.preferredWidth
        }

        val tableWidth = parent?.let { (it as? JViewport)?.width } ?: width
        val remainingWidth = maxOf(tableWidth - contentWidth, 200)
        columnModel.getColumn(lastColIndex).preferredWidth = remainingWidth
    }

    private fun clearAll() {
        inputArea.text = ""
        summaryModel.rowCount = 0
        detailsModel.rowCount = 0
        detailsTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        summaryTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        rawTextArea.text = ""
        rightTabs.setTitleAt(0, "Fields")
        filePathField.text = "No file selected"
        filePathField.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        parsedResult = null
        filterField.text = ""
        regexEnabled = false
        negateEnabled = false
        filterField.background = defaultFilterBg
        filterField.repaint()
        sorter.rowFilter = null
        messageCountLabel.text = ""
    }

    private fun applyFilter() {
        val text = filterField.text
        if (text.isBlank()) {
            filterField.background = defaultFilterBg
            sorter.rowFilter = null
            updateCountLabel()
            return
        }
        val matcher: (String) -> Boolean = if (regexEnabled) {
            val regex = try {
                Regex(text, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                filterField.background = JBColor.RED
                return
            }
            filterField.background = defaultFilterBg
            { raw -> regex.containsMatchIn(raw) }
        } else {
            filterField.background = defaultFilterBg
            { raw -> raw.contains(text, ignoreCase = true) }
        }
        val negate = negateEnabled
        sorter.rowFilter = object : javax.swing.RowFilter<DefaultTableModel, Int>() {
            override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean {
                val raw = parsedResult?.messages?.getOrNull(entry.identifier)?.rawMessage ?: return true
                val matches = matcher(raw)
                return if (negate) !matches else matches
            }
        }
        updateCountLabel()
    }

    private fun updateCountLabel() {
        val total = parsedResult?.messages?.size ?: 0
        messageCountLabel.text = if (total == 0) ""
        else if (sorter.rowFilter == null) "$total"
        else "${summaryTable.rowCount} / $total"
    }
}
