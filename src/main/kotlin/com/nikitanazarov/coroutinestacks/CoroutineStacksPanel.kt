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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutineDebugProbesProxy
import java.awt.*
import javax.swing.*


class CoroutineStacksPanel(project: Project) : JBPanelWithEmptyText() {
    private var coroutineGraph: JComponent? = null

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
                            println("paused")
                            emptyText.component.isVisible = false
                            buildCoroutineGraph(suspendContext)
                        }

                        override fun resumed(suspendContext: SuspendContext?) {
                            println("resumed")
                            remove(coroutineGraph)
                        }
                    })
                }

                override fun sessionRemoved(session: DebuggerSession) {
                    emptyText.text = CoroutineStacksBundle.message("no.java.debug.process.is.running")
                    emptyText.component.isVisible = true
                    remove(coroutineGraph)
                }
            })
    }

    private fun buildCoroutineGraph(suspendContext: SuspendContext) {

        val coroutineInfoCache = CoroutineDebugProbesProxy(suspendContext as SuspendContextImpl).dumpCoroutines()

        val coroutineInfoDataList = coroutineInfoCache.cache
        val dispatchers = mutableSetOf<String>()

        val dispatcherToCoroutineDataListMap = mutableMapOf<String, MutableList<CoroutineInfoData>>()

        for (i in coroutineInfoDataList) {
            if (dispatcherToCoroutineDataListMap[i.descriptor.dispatcher!!] == null) {
                dispatcherToCoroutineDataListMap[i.descriptor.dispatcher!!] = mutableListOf()
            }
            println("coroutineInfoDataList (Active Thread): ${i.activeThread}")
            println("coroutineInfoDataList (Creation Stack Trace): ${i.creationStackTrace}")
            println("coroutineInfoDataList (Stack Trace): ${i.stackTrace}")
            println()

            for (j in i.stackTrace) {
                println("stacktrace $j, stacktracelocation: ${j.location}")
            }

            println()
            println("coroutineInfoDataList (descriptor): ${i.descriptor}")
            println("coroutineInfoDataList (Top Frame Variables): ${i.topFrameVariables}")
            println()

            dispatcherToCoroutineDataListMap[i.descriptor.dispatcher]?.add(i)
            i.descriptor.dispatcher?.let { dispatchers.add(it) }
        }

        println("cache state: ${coroutineInfoCache.state}")

        val dispatcherList = dispatchers.toTypedArray()
        val mapOfParallelStackTree = mutableMapOf<String, Tree<ParallelStackNode>>()

        for (i in dispatcherList) {

            val tree = Tree<ParallelStackNode>()

            val positionOfStackFrame = 0

            val rootValue =
                dispatcherToCoroutineDataListMap[i]?.let { ParallelStackNode(stacktrace = mutableListOf(), additionalData = it) }
            if (rootValue != null) {
                tree.insert(rootValue)
            }

            println("dispatcherToCoroutineDataListMap[i]: ${dispatcherToCoroutineDataListMap[i]}")
            println("root value: $rootValue")
            println("i dispatcher: $i")

            generateParallelStackTree(tree, rootValue, positionOfStackFrame)

            printTree(tree.root, 0)

            mapOfParallelStackTree[i] = tree

        }

        val dispatcherLabel = JLabel("Select Dispatcher:  ")

        val parallelStackWindowHeader = Box.createHorizontalBox()

        val dispatcherDropdownMenu = ComboBox(dispatcherList)
        val comboBoxSize = Dimension(150, 25)
        dispatcherDropdownMenu.preferredSize = comboBoxSize
        dispatcherDropdownMenu.maximumSize = comboBoxSize
        dispatcherDropdownMenu.minimumSize = comboBoxSize

        dispatcherDropdownMenu.addActionListener{
            val selectedItem = dispatcherDropdownMenu.selectedItem
        }

        parallelStackWindowHeader.add(dispatcherLabel)
        parallelStackWindowHeader.add(dispatcherDropdownMenu)

        coroutineGraph = Box.createVerticalBox()
        coroutineGraph?.add(parallelStackWindowHeader)

        val parallelStackWindow = Box.createVerticalBox()
        buildParallelStackWindow(mapOfParallelStackTree, parallelStackWindow, dispatcherList)

        coroutineGraph?.add(parallelStackWindow)

        add(coroutineGraph)

    }

    private fun buildParallelStackWindow(
        mapOfParallelStackTree: MutableMap<String, Tree<ParallelStackNode>>,
        parallelStackWindow: Box?,
        dispatcherList: Array<String>
    ) {

        val tree = mapOfParallelStackTree[dispatcherList[0]]
        val lengthOfTree = getTreeLength(tree?.root)

        val rows = mutableListOf<Box>()
        for (i in 1..lengthOfTree) {
            rows.add(Box.createHorizontalBox())
        }

        addCoroutineInfoToParallelStackWindow(tree?.root, rows, 0)

        for (i in rows.reversed()) {
            parallelStackWindow?.add(i)
            parallelStackWindow?.add(Box.createVerticalStrut(25))
        }

    }

    private fun addCoroutineInfoToParallelStackWindow(
        node: CoroutineStacksPanel.TreeNode<CoroutineStacksPanel.ParallelStackNode>?,
        rows: MutableList<Box>,
        level: Int) {

        if (node == null) {
            return
        }

//        rows[level].add(JButton(node.value.toString()))
        if (node.value.stacktrace.isNotEmpty()) {
            addCoroutineInfoBox(node.value, rows[level])
        }
        for (child in node.children) {
            addCoroutineInfoToParallelStackWindow(child, rows,level + 1)
        }

    }

    private fun addCoroutineInfoBox(
        value: CoroutineStacksPanel.ParallelStackNode,
        box: Box) {

        val headerText = "${value.additionalData.size} Coroutines"
        val coroutineList = mutableListOf<String>()
        coroutineList.add(headerText)

        for (i in value.stacktrace) {
            i?.let { coroutineList.add(it) }
        }

        val coroutineListView = JBList<String>(coroutineList)

//        var maxLengthStackTrace = 0
//        for (i in value.stacktrace) {
//            if (i?.length!! > maxLengthStackTrace) {
//                maxLengthStackTrace = i.length
//            }
//        }

//        val headerBox = JLabel(headerText)
//        headerBox.border = BorderFactory.createCompoundBorder(
//            LineBorder(Color.BLACK, 1),
//            EmptyBorder(2, 2, 2, 2)
//        )
//
//        headerBox.font = Font("Arial", Font.ITALIC, 14)

//        childBox.add(headerBox)
//
//        for (i in value.stacktrace) {
////            val childBoxFrame = JLabel(addWhiteSpaces(i, maxLengthStackTrace - i!!.length))
//            val childBoxFrame = JLabel(i)
//            childBoxFrame.border = BorderFactory.createCompoundBorder(
//                LineBorder(Color.BLACK, 1),
//                EmptyBorder(2, 4, 2, 4)
//            )
//            childBox.add(childBoxFrame)
//        }

        coroutineListView.setCellRenderer(SeparatedListCellRenderer())
        val scrollPane = JBScrollPane(coroutineListView)

        // Create a border for the scroll pane

        // Create a border for the scroll pane
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

    private fun addWhiteSpaces(i: String?, spaces: Int): String? {
        var response = i
        for (i in 1..spaces) {
            response += " "
        }
        return response
    }

    private fun generateParallelStackTree(
        tree: CoroutineStacksPanel.Tree<CoroutineStacksPanel.ParallelStackNode>,
        rootValue: CoroutineStacksPanel.ParallelStackNode?,
        positionOfStackFrame: Int) {

        val idToStackFramesMap = mutableMapOf<CoroutineInfoData, String>()
        if (rootValue != null) {
            for (k in rootValue.additionalData!!) {
                if (k.stackTrace.size > positionOfStackFrame)
                    idToStackFramesMap[k] = k.stackTrace[k.stackTrace.size -1 - positionOfStackFrame].toString()
            }
        }

        println("idToStackFramesMap: $idToStackFramesMap")

        val groupedPositions = idToStackFramesMap.entries.groupBy { it.value }.values.map { list ->
            list.map { it.key }
        }
        println("grouped positions: $groupedPositions")

        for (i in groupedPositions) {
            if (rootValue != null) {
                if (groupedPositions.size == 1 && rootValue.stacktrace.isNotEmpty()) {
                    rootValue?.stacktrace?.add(idToStackFramesMap[i[0]])
                    generateParallelStackTree(tree, rootValue, positionOfStackFrame + 1)
                } else if (groupedPositions.size > 1 || rootValue?.stacktrace?.isEmpty() == true) {
                    val childValue = ParallelStackNode(stacktrace = mutableListOf(idToStackFramesMap[i[0]]), additionalData = i)
                    println("childValue in recursion: $childValue")
                    println()
                    if (rootValue != null) {
                        tree.insert(childValue, rootValue)
                    }
                    generateParallelStackTree(tree, childValue, positionOfStackFrame + 1)
                }
            }
        }

    }

    data class ParallelStackNode(
        val stacktrace: MutableList<String?>,
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
    }

    private fun printTree(node: TreeNode<ParallelStackNode>?, level: Int) {
        if (node == null) {
            println("no children")
            return
        }

        println("${"\t".repeat(level)}- Node: ${node.value}")
        for (child in node.children) {
            printTree(child, level + 1)
        }
    }

    private fun <T> getTreeLength(root: TreeNode<T>?): Int {
        if (root == null) {
            return 0
        }

        var length = 1

        for (child in root.children) {
            length += getTreeLength(child)
        }

        return length
    }

    private fun drawArrowBetweenComponents(container: Container) {
        val arrowComponent = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)

                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

//                val sourceBounds = SwingUtilities.convertRectangle(source.parent, source.bounds, container)
//                val targetBounds = SwingUtilities.convertRectangle(target.parent, target.bounds, container)

//                val x1 = sourceBounds.x + sourceBounds.width / 2
//                val y1 = sourceBounds.y + sourceBounds.height
//                val x2 = targetBounds.x + targetBounds.width / 2
//                val y2 = targetBounds.y

                g2d.color = Color.RED
                g2d.stroke = BasicStroke(2.0f)

                g2d.drawLine(0, 0, 5, 5)

                val arrowSize = 4
                val arrowhead = Polygon()
                arrowhead.addPoint(5, 5)
                arrowhead.addPoint(5 - arrowSize, 5 - arrowSize)
                arrowhead.addPoint(5 + arrowSize, 5 - arrowSize)
                g2d.fill(arrowhead)
            }
        }
        container.add(arrowComponent)
    }
}