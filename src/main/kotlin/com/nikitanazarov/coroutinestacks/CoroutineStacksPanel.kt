package com.nikitanazarov.coroutinestacks

import com.intellij.debugger.engine.DebugProcessListener
import com.intellij.debugger.engine.SuspendContext
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.nikitanazarov.coroutinestacks.ui.ContainerWithEdges
import com.nikitanazarov.coroutinestacks.ui.ForestLayout
import com.nikitanazarov.coroutinestacks.ui.Separator
import com.sun.jdi.Location
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutineDebugProbesProxy
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.util.*
import javax.swing.*

// Currently, the code is not looking good, pushing code only to save progress
// make necessary changes in the next commit
class CoroutineStacksPanel(project: Project) : JBPanelWithEmptyText() {
    class CustomCellRenderer(private val coroutinesActive: String?) : DefaultListCellRenderer() {
        private val ITEM_BORDER = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.GRAY)

        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val renderer = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            if (index == 0) {
                (renderer as? JComponent)?.apply {
                    toolTipText = coroutinesActive
                    font = renderer.font.deriveFont(Font.BOLD)
                }
            }

            if (index < list.model.size - 1) {
                (renderer as? JComponent)?.border = ITEM_BORDER
            } else {
                (renderer as? JComponent)?.border = null
            }

            return renderer
        }
    }


    private val coroutineGraph = Box.createVerticalBox()

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
        val coroutineInfoCache = CoroutineDebugProbesProxy(suspendContextImpl).dumpCoroutines()

        val coroutineInfoDataList = coroutineInfoCache.cache
        val dispatchers = mutableSetOf<String>()

        val dispatcherToCoroutineDataList = mutableMapOf<String, MutableList<CoroutineInfoData>>()

        for (info in coroutineInfoDataList) {
            val dispatcher = info.descriptor.dispatcher ?: continue
            dispatcherToCoroutineDataList.computeIfAbsent(dispatcher) { mutableListOf() }.add(info)
            dispatchers.add(dispatcher)
        }

        val dispatcherToCoroutineStacksTree = mutableMapOf<String, Tree<CoroutineStacksNode>>()

        for (dispatcher in dispatchers) {
            val rootValue =
                CoroutineStacksNode(stackTrace = mutableListOf(), additionalData = dispatcherToCoroutineDataList[dispatcher]!!)
            val tree = Tree(TreeNode(rootValue))

            val root = Node(null, -1, mutableMapOf(), "")
            generateParallelStackTree( root, coroutineInfoDataList)

            printTree(tree.root, 0)

            dispatcherToCoroutineStacksTree[dispatcher] = tree
        }

//        buildCoroutineStacksToolWindowView(dispatchers, dispatcherToCoroutineStacksTree)
    }

    private fun buildCoroutineStacksView(
        mapOfParallelStackTree: MutableMap<String, Tree<CoroutineStacksNode>>,
        coroutineStacksView: Box,
        dispatchers: MutableSet<String>
    ) {
        if (dispatchers.isEmpty()) {
            return
        }

        // code is incomplete, a coroutine stack view will show graphs for all possible dispatchers, but here
        // the graph will be shown for only the first dispatcher in the list.
        // Implement the functionality to build graph for all dispatchers. Fix this in next commit.

        val dispatchersList = dispatchers.toTypedArray()
        val tree = mapOfParallelStackTree[dispatchersList[0]] ?: return

        val rows = mutableListOf<Box>()
        for (i in 1..tree.getHeight()) {
            rows.add(Box.createHorizontalBox())
        }

        addCoroutineInfoToCoroutineStackWindow(tree.root, rows)

        for (row in rows.reversed()) {
            coroutineStacksView.add(row)
            coroutineStacksView.add(Box.createVerticalStrut(Constants.boxVerticalStruct))
        }
    }

    private fun addCoroutineInfoToCoroutineStackWindow(
        rootNode: TreeNode<CoroutineStacksNode>,
        rows: MutableList<Box>
    ) {
        val stack = Stack<Pair<TreeNode<CoroutineStacksNode>, Int>>()
        stack.push(rootNode to 0)

        while (stack.isNotEmpty()) {
            val (node, level) = stack.pop()

            if (node.value.stackTrace.isNotEmpty()) {
                addCoroutineInfoBox(node.value, rows[level])
            }

            for (child in node.children) {
                stack.push(child to level + 1)
            }
        }
    }

    private fun addCoroutineInfoBox(node: CoroutineStacksNode, box: Box) {
        val headerText = CoroutineStacksBundle.message("number.of.coroutines", node.additionalData.size)
        val stackFrames = mutableListOf<String>()
        stackFrames.add(headerText)

        stackFrames.addAll(node.stackTrace)

        val coroutineListView = JBList<String>(stackFrames)

//        coroutineListView.cellRenderer = cellRenderer

        val scrollPane = JBScrollPane(coroutineListView)

        val border = BorderFactory.createLineBorder(JBColor.BLACK, Constants.borderWidth)
        scrollPane.border = border

        box.add(scrollPane)
        box.add(Box.createHorizontalStrut(Constants.boxHorizontalStruct))
    }

    private fun generateParallelStackTree(
        rootValue: Node,
        coroutineDataList: MutableList<CoroutineInfoData>
    ) {
        for (coroutineData in coroutineDataList) {
            var currentNode = rootValue
            for (stackFrame in coroutineData.stackTrace.reversed()) {
                val child = currentNode.children[stackFrame.location]
                if (child != null) {
                    child.num += 1
                    println("lolp: ${coroutineData.descriptor.name}${coroutineData.descriptor.id} ${coroutineData.descriptor.state} \n")
                    child.coroutinesActive += "${coroutineData.descriptor.name}${coroutineData.descriptor.id} ${coroutineData.descriptor.state} \n"
                    currentNode = child
                } else {
                    println("lolp: ${coroutineData.descriptor.name}${coroutineData.descriptor.id} ${coroutineData.descriptor.state} \n")
                    val node = Node(stackFrame.location,1, mutableMapOf(),
                        "${coroutineData.descriptor.name}${coroutineData.descriptor.id} ${coroutineData.descriptor.state} \n"
                    )
                    currentNode.children[stackFrame.location] = node
                    currentNode = node
                }
            }
        }

        var treeList: MutableList<MutableList<String>> = mutableListOf()
        val stack = Stack<Pair<Node, Int>>()
        val parentStack = Stack<Node>() // Stack to track parent nodes
        val treeListHeaders: MutableList<String> = mutableListOf()
        val treeListHeaderHoverText: MutableList<String?> = mutableListOf()

        var previousLevel = 1
        var currentLevel = 1
        var separatorCount = 1

        stack.push(Pair(rootValue, 0))
        val separList = mutableListOf<String>()
        var noOfSeparInEnd = 0
        var k = -1

        println("root $rootValue")
        while (stack.isNotEmpty()) {
            val (currentNode, level) = stack.pop()
            val parent = if (parentStack.isNotEmpty()) parentStack.pop() else null
            val indentation = "  ".repeat(level) // Indentation based on the level
            println("$indentation StackFrame: ${currentNode.stackFrameLocation}, Num: ${currentNode.num}")
            noOfSeparInEnd = level

            if (parent != null) {
//                currentLevel = level
//
//                if (previousLevel == currentLevel) {
//                    for (i in 1..separatorCount) {
//                        treeList.add(mutableListOf("separator"))
//                        k += 1
//                    }
//                    separatorCount = 1
//                    if (parent.num != currentNode.num) {
//                        previousLevel = currentLevel
//                    }
//                } else {
//                    separatorCount += 1
//                }

                if (parent.num != currentNode.num) {
                    treeListHeaders.add("${currentNode.num} Coroutines ACTIVE")
                    println("currentNode coroutinesActive: ${currentNode.coroutinesActive}")
                    treeListHeaderHoverText.add(currentNode.coroutinesActive)
                    treeList.add(mutableListOf(currentNode.stackFrameLocation.toString()))
                    k += 1
                } else {
                    treeList[k].add(currentNode.stackFrameLocation.toString())
                }
            }

            // Add children to the stack in reverse order with an incremented level
            var p = 0
            currentNode.children.values.reversed().forEach { child ->
                if (p != currentNode.children.values.size-1) {
                    separList.add(child.stackFrameLocation.toString())
                }
                stack.push(Pair(child, level + 1))
                parentStack.push(currentNode) // Push the current node as the parent for its children
                p += 1
            }
        }

        val newTreeList: MutableList<MutableList<String>> = mutableListOf()
        for (treeItem in treeList) {
            if (separList.contains(treeItem[0])) {
                newTreeList.add(mutableListOf("separ"))
            }
            newTreeList.add(treeItem)
        }
        for (a in 1..treeList.size - separList.size) {
            newTreeList.add(mutableListOf("separ"))
        }

        println("treeList $newTreeList")
        treeList = newTreeList

        val treeForestList: MutableList<Component> = mutableListOf()
//
        var treeListHeaderCount = 0
        for (i in 0 until newTreeList.size) {
            val vertex = JBList<String>()
            val data = mutableListOf<String>()
            if (newTreeList[i][0] == "separ") {
                treeForestList.add(Separator())
            } else {
                data.add(treeListHeaders[treeListHeaderCount])
                for (j in 0 until newTreeList[i].size) {
                    data.add(newTreeList[i][j])
                }
                vertex.setListData(data.toTypedArray())
                vertex.border = BorderFactory.createLineBorder(Color.BLACK)
                println("hover data: ${treeListHeaderHoverText[treeListHeaderCount]}")
                vertex.cellRenderer = CustomCellRenderer(treeListHeaderHoverText[treeListHeaderCount])
                treeForestList.add(vertex)
                treeListHeaderCount += 1
            }
        }
//
        val forest = ContainerWithEdges()
        treeForestList.forEach { forest.add(it) }
        forest.layout = ForestLayout()
        coroutineGraph.add(JBScrollPane(forest))
        add(coroutineGraph)

//        val dataToStackFrame = mutableMapOf<CoroutineInfoData, String>()
//        if (rootValue == null) {
//            return
//        }
//
//        for (data in rootValue.additionalData) {
//            if (data.stackTrace.size > positionOfStackFrame)
//                dataToStackFrame[data] = data.stackTrace[data.stackTrace.size -1 - positionOfStackFrame].toString()
//        }
//
//        val entries = dataToStackFrame.entries
//
//        val groupedByValue = entries.groupBy { it.value }
//
//        val groupedPositions = groupedByValue.map { (_, list) ->
//            list.map { it.key }
//        }
//
//        for (groupedPosition in groupedPositions) {
//            if (entries.size == 1 && rootValue.stackTrace.isNotEmpty()) {
//                rootValue.stackTrace.add(dataToStackFrame[groupedPosition[0]]!!)
//                generateParallelStackTree(tree, rootValue, positionOfStackFrame + 1)
//            } else if (entries.size > 1) {
//                val childValue = CoroutineStacksNode(stackTrace = mutableListOf(dataToStackFrame[groupedPosition[0]]!!), additionalData = groupedPosition)
//                println("childValue in recursion: $childValue")
//                println()
//                tree.insert(childValue, rootValue)
//                generateParallelStackTree(tree, childValue, positionOfStackFrame + 1)
//            }
//        }
    }

    data class Node(
        val stackFrameLocation: Location?,
        var num: Int,
        val children: MutableMap<Location, Node>,
        var coroutinesActive: String
    )

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

    class Tree<T>(val root: TreeNode<T>) {

        fun insert(value: T, parentValue: T) {
            val newNode = TreeNode(value)
            val parentNode = findNode(root, parentValue)
            parentNode?.addChild(newNode)
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
            return root.getHeight()
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

    val stack = Stack<Pair<CoroutineStacksPanel.TreeNode<T>, Int>>()
    stack.push(this to 1)
    var maxHeight = 0

    while (stack.isNotEmpty()) {
        val (node, height) = stack.pop()

        if (height > maxHeight) {
            maxHeight = height
        }

        for (child in node.children) {
            stack.push(child to height + 1)
        }
    }

    return maxHeight
}

fun createList(): JList<String> {
    val list = JBList<String>()
    val random = Random()
    val data = mutableListOf<String>()
    val size = random.nextInt(20) + 1
    for (i in 0 until size) {
        data.add("com.google.sandbox.foo()")
    }
    list.setListData(data.toTypedArray())
    return list
}
