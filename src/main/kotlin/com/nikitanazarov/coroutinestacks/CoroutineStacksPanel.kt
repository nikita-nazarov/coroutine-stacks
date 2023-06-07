package com.nikitanazarov.coroutinestacks

import com.intellij.debugger.engine.DebugProcessListener
import com.intellij.debugger.engine.SuspendContext
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutineDebugProbesProxy
import java.awt.*
import javax.swing.*
import kotlin.script.experimental.api.ResultValue


class CoroutineStacksPanel(project: Project) : JBPanelWithEmptyText() {
    val coroutineGraph = Box.createVerticalBox()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        emptyText.text = CoroutineStacksBundle.message("no.java.debug.process.is.running")
        project.messageBus.connect()
            .subscribe<DebuggerManagerListener>(DebuggerManagerListener.TOPIC, object : DebuggerManagerListener {
                override fun sessionAttached(session: DebuggerSession?) {
                    emptyText.text = CoroutineStacksBundle.message("should.be.stopped.on.a.breakpoint")
                }

                override fun sessionCreated(session: DebuggerSession) {
                    session.process.addDebugProcessListener(object : DebugProcessListener {
                        override fun paused(suspendContext: SuspendContext) {
                            emptyText.component.isVisible = false
                            buildCoroutineGraph(suspendContext)
                        }

                        override fun resumed(suspendContext: SuspendContext?) {
                            coroutineGraph.removeAll()
                        }
                    })
                }

                override fun sessionRemoved(session: DebuggerSession) {
                    emptyText.text = CoroutineStacksBundle.message("no.java.debug.process.is.running")
                    emptyText.component.isVisible = true
                    coroutineGraph.removeAll()
                }
            })
    }

    private fun buildCoroutineGraph(suspendContext: SuspendContext) {
        val suspendContextImpl = suspendContext as? SuspendContextImpl ?: run {
            emptyText.text = CoroutineStacksBundle.message("coroutine.stacks.could.not.be.built")
            return
        }

        val coroutineInfoCache = CoroutineDebugProbesProxy(suspendContext as SuspendContextImpl).dumpCoroutines()

        val coroutineInfoDataList = coroutineInfoCache.cache
        val dispatchers = mutableSetOf<String>()

        val dispatcherToCoroutineDataList = mutableMapOf<String, MutableList<CoroutineInfoData>>()

        for (info in coroutineInfoDataList) {
            val dispatcher = info.descriptor.dispatcher ?: continue
            dispatcherToCoroutineDataList.computeIfAbsent(dispatcher) { mutableListOf() }.add(info)
            dispatchers.add(dispatcher)
        }

        val dispatcherList = dispatchers.toTypedArray()
        val dispatcherToCoroutineStacksTree = mutableMapOf<String, Tree<CoroutineStacksNode>>()

        for (dispatcher in dispatcherList) {

            val tree = Tree<CoroutineStacksNode>()

            val rootValue =
                dispatcherToCoroutineDataList[dispatcher]?.let { CoroutineStacksNode(stackTrace = mutableListOf(), additionalData = it) }
            if (rootValue != null) {
                tree.insert(rootValue)
            }

            generateParallelStackTree(tree, rootValue, 0)

            printTree(tree.root, 0)

            dispatcherToCoroutineStacksTree[dispatcher] = tree

        }

        buildCoroutineStacksToolWindowView(dispatcherList, dispatcherToCoroutineStacksTree)

    }

    private fun buildCoroutineStacksToolWindowView(
        dispatcherList: Array<String>,
        dispatcherToCoroutineStacksTree: MutableMap<String, Tree<CoroutineStacksNode>>
    ) {
        val dispatcherLabel = JLabel(CoroutineStacksBundle.message("select.dispatcher"))

        val coroutineStacksWindowHeader = Box.createHorizontalBox()

        val dispatcherDropdownMenu = ComboBox(dispatcherList)
        val comboBoxSize = Dimension(150, 25)
        dispatcherDropdownMenu.preferredSize = comboBoxSize
        dispatcherDropdownMenu.maximumSize = comboBoxSize
        dispatcherDropdownMenu.minimumSize = comboBoxSize

        dispatcherDropdownMenu.addActionListener {
            val selectedItem = dispatcherDropdownMenu.selectedItem
        }

        coroutineStacksWindowHeader.add(dispatcherLabel)
        coroutineStacksWindowHeader.add(dispatcherDropdownMenu)

        coroutineGraph?.add(coroutineStacksWindowHeader)

        val coroutineStacksView = Box.createVerticalBox()
        buildCoroutineStacksView(dispatcherToCoroutineStacksTree, coroutineStacksView, dispatcherList)

        coroutineGraph?.add(coroutineStacksView)

        add(coroutineGraph)
}

    private fun buildCoroutineStacksView(
        mapOfParallelStackTree: MutableMap<String, Tree<CoroutineStacksNode>>,
        coroutineStacksView: Box,
        dispatcherList: Array<String>
    ) {

        if (dispatcherList.isEmpty()) {
            return
        }

        val tree = mapOfParallelStackTree[dispatcherList[0]] ?: return
        val rows = mutableListOf<Box>()
        for (i in 1..tree.getHeight()) {
            rows.add(Box.createHorizontalBox())
        }

        tree.root?.let { addCoroutineInfoToParallelStackWindow(it, rows, 0) }

        for (i in rows.reversed()) {
            coroutineStacksView?.add(i)
            coroutineStacksView?.add(Box.createVerticalStrut(25))
        }

    }

    private fun addCoroutineInfoToParallelStackWindow(
        node: CoroutineStacksPanel.TreeNode<CoroutineStacksPanel.CoroutineStacksNode>,
        rows: MutableList<Box>,
        level: Int
    ) {

        if (node == null) {
            return
        }

        if (node.value.stackTrace.isNotEmpty()) {
            addCoroutineInfoBox(node.value, rows[level])
        }
        for (child in node.children) {
            addCoroutineInfoToParallelStackWindow(child, rows,level + 1)
        }

    }

    private fun addCoroutineInfoBox(
        value: CoroutineStacksPanel.CoroutineStacksNode,
        box: Box
    ) {

        val headerText = "${value.additionalData.size} Coroutines"
        val stackFrames = mutableListOf<String>()
        stackFrames.add(headerText)

        for (i in value.stackTrace) {
            i?.let { stackFrames.add(it) }
        }

        val coroutineListView = JBList<String>(stackFrames)

        coroutineListView.cellRenderer = SeparatedListCellRenderer()
        val scrollPane = JBScrollPane(coroutineListView)

        val border = BorderFactory.createLineBorder(Color.BLACK, 1)
        scrollPane.border = border

        box.add(scrollPane)
        box.add(Box.createHorizontalStrut(120))

    }

    internal class SeparatedListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val renderer = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (index < list.model.size - 1) {
                (renderer as JComponent).border = ITEM_BORDER
            } else {
                (renderer as JComponent).border = null
            }
            return renderer
        }

        companion object {
            private val ITEM_BORDER = BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY)
        }
    }

    private fun generateParallelStackTree(
        tree: CoroutineStacksPanel.Tree<CoroutineStacksPanel.CoroutineStacksNode>,
        rootValue: CoroutineStacksPanel.CoroutineStacksNode?,
        positionOfStackFrame: Int
    ) {

        val dataToStackFrame = mutableMapOf<CoroutineInfoData, String>()
        if (rootValue == null) {
            return
        }
            for (data in rootValue.additionalData) {
                if (data.stackTrace.size > positionOfStackFrame)
                    dataToStackFrame[data] = data.stackTrace[data.stackTrace.size -1 - positionOfStackFrame].toString()
            }

        val groupedPositions = dataToStackFrame.entries.groupBy { it.value }.values.map { list ->
            list.map { it.key }
        }
        println("grouped positions: $groupedPositions")

        for (i in groupedPositions) {
                if (groupedPositions.size == 1 && rootValue.stackTrace.isNotEmpty()) {
                    rootValue?.stackTrace?.add(dataToStackFrame[i[0]]!!)
                    generateParallelStackTree(tree, rootValue, positionOfStackFrame + 1)
                } else if (groupedPositions.size > 1 || rootValue.stackTrace.isEmpty()) {
                    val childValue = CoroutineStacksNode(stackTrace = mutableListOf(dataToStackFrame[i[0]]!!), additionalData = i)
                    println("childValue in recursion: $childValue")
                    println()
                    tree.insert(childValue, rootValue)
                    generateParallelStackTree(tree, childValue, positionOfStackFrame + 1)
                }
        }

    }

    data class CoroutineStacksNode(
        val stackTrace: MutableList<String>,
        val additionalData: List<CoroutineInfoData>
    )

    class TreeNode<T>(val value: T) {
        val children: MutableList<TreeNode<T>> = mutableListOf()

        fun addChild(child: TreeNode<T>) {
            children.add(child)
        }
    }

    class Tree<T> {
        var root: TreeNode<T>? = null

        fun insert(value: T) {
            val newNode = TreeNode(value)
            if (root == null) {
                root = newNode
            } else {
                throw UnsupportedOperationException("Insertion in a specific position is required for a general tree.")
            }
        }

        fun insert(value: T, parentValue: T) {
            val newNode = TreeNode(value)
            if (root == null) {
                root = newNode
            } else {
                val parentNode = findNode(root!!, parentValue)
                parentNode?.addChild(newNode)
            }
        }

        private fun findNode(node: TreeNode<T>, value: T): TreeNode<T>? {
            if (node.value == value) {
                return node
            }
            for (child in node.children) {
                val result = findNode(child, value)
                if (result != null) {
                    return result
                }
            }
            return null
        }

        fun getHeight() : Int {
            return root?.getHeight() ?: 0
        }
    }

    private fun printTree(node: TreeNode<CoroutineStacksNode>?, level: Int) {
        if (node == null) {
            println("no children")
            return
        }

        println("${"\t".repeat(level)}- Node: ${node.value}")
        for (child in node.children) {
            printTree(child, level + 1)
        }
    }
}

private fun <T> CoroutineStacksPanel.TreeNode<T>.getHeight(): Int {
    if (children.isEmpty()) {
        return 0
    }

    var maxHeight = 0
    for (child in children) {
        val height = child.getHeight()
        if (height > maxHeight) {
            maxHeight = height
        }
    }

    return maxHeight + 1
}
