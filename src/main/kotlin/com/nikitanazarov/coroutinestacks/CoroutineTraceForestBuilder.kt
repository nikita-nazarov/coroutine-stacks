package com.nikitanazarov.coroutinestacks

import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.util.preferredWidth
import com.intellij.util.ui.JBUI
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.nikitanazarov.coroutinestacks.ui.ContainerWithEdges
import com.nikitanazarov.coroutinestacks.ui.ForestLayout
import com.nikitanazarov.coroutinestacks.ui.Separator
import com.sun.jdi.Location
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsClassFinder
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutine.data.State
import org.jetbrains.kotlin.idea.debugger.coroutine.util.CoroutineFrameBuilder
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.border.Border
import javax.swing.border.LineBorder

data class Node(
    val stackFrameItem: CoroutineStackFrameItem? = null,
    var num: Int = 0, // Represents how many coroutines have this frame in their stack trace
    val children: MutableMap<Location, Node> = mutableMapOf(),
    var coroutinesActive: String = ""
)

data class CoroutineTrace(val stackFrameItems: MutableList<CoroutineStackFrameItem?>, val header: String, val coroutinesActiveLabel: String)

private fun CoroutineInfoData.render(): String =
    "${descriptor.name}${descriptor.id} ${descriptor.state}\n"

fun SuspendContextImpl.buildCoroutineStackForest(
    rootValue: Node,
    coroutineDataList: MutableList<CoroutineInfoData>
): JBScrollPane? {
    val lastRunningStackFrame = buildStackFrameGraph(coroutineDataList, rootValue)
    val coroutineTraces = createCoroutineTraces(rootValue)
    return createCoroutineTraceForest(coroutineTraces, lastRunningStackFrame)
}

private fun SuspendContextImpl.createCoroutineTraceForest(
    traces: List<CoroutineTrace?>,
    lastRunningStackFrame: String
): JBScrollPane? {
    if (traces.isEmpty()) return null
    val vertexData: MutableList<JBList<String>?> = mutableListOf()
    val componentData: MutableList<Component> = mutableListOf()
    var previousListSelection: JBList<*>? = null
    var maxWidth = 0
    var traceNotNullCount = 0

    traces.forEach { trace ->
        if (trace == null) {
            vertexData.add(null)
            return@forEach
        }

        val vertex = CoroutineTraceUI(trace, lastRunningStackFrame, this).getVertex()
        vertex.addListSelectionListener { e ->
            val currentList = e.source as? JBList<*> ?: return@addListSelectionListener
            if (previousListSelection != currentList) {
                previousListSelection?.clearSelection()
            }
            previousListSelection = currentList
        }
        vertexData.add(vertex)
        maxWidth += vertex.preferredWidth
        traceNotNullCount += 1
    }

    val averagePreferredWidth = maxWidth / traceNotNullCount
    vertexData.forEach { vertex ->
        if (vertex != null) {
            vertex.preferredWidth = averagePreferredWidth
            componentData.add(vertex)
            return@forEach
        }
        componentData.add(Separator())
    }

    val forest = ContainerWithEdges()
    componentData.forEach { forest.add(it) }
    forest.layout = ForestLayout()

    return JBScrollPane(forest)
}

private fun createCoroutineTraces(rootValue: Node): List<CoroutineTrace?> {
    val stack = Stack<Pair<Node, Int>>().apply { push(rootValue to 0) }
    val parentStack = Stack<Node>()
    var previousLevel: Int? = null
    val coroutineTraces = mutableListOf<CoroutineTrace?>()

    while (stack.isNotEmpty()) {
        val (currentNode, currentLevel) = stack.pop()
        val parent = if (parentStack.isNotEmpty()) parentStack.pop() else null

        if (parent != null && parent.num != currentNode.num) {
            val currentTrace = CoroutineTrace(
                mutableListOf(currentNode.stackFrameItem),
                CoroutineStacksBundle.message("number.of.coroutines.active", currentNode.num),
                currentNode.coroutinesActive
            )
            repeat((previousLevel ?: 0) - currentLevel + 1) {
                coroutineTraces.add(null)
            }
            coroutineTraces.add(currentTrace)
            previousLevel = currentLevel
        } else if (parent != null) {
            coroutineTraces.lastOrNull()?.stackFrameItems?.add(0, currentNode.stackFrameItem)
        }

        currentNode.children.values.reversed().forEach { child ->
            stack.push(child to (currentLevel + 1))
            parentStack.push(currentNode)
        }
    }

    return coroutineTraces
}

private class CoroutineTraceUI(
    private val trace: CoroutineTrace,
    private val lastRunningStackFrame: String,
    private val suspendContextImpl: SuspendContextImpl
    ) {
    private val vertex: JBList<String> = createVertex()

    init {
        setupUI()
    }

    fun getVertex(): JBList<String> {
        return vertex
    }

    private fun createRoundedBorder(color: JBColor): Border {
        val cornerRadius = Constants.cornerRadius
        val borderThickness = Constants.borderThickness

        val roundedBorder = object : LineBorder(color, borderThickness) {
            override fun getBorderInsets(c: Component?): Insets {
                val insets = super.getBorderInsets(c)
                return JBUI.insets(insets.top, insets.left, insets.bottom, insets.right)
            }

            override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
                val g2d = g as? Graphics2D ?: return
                val arc = 2 * cornerRadius
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.color = color
                g2d.drawRoundRect(x, y, width - 1, height - 1, arc, arc)
            }
        }

        return roundedBorder
    }

    private fun createVertex(): JBList<String> {
        val vertex = JBList<String>()
        val data = mutableListOf<String>()
        data.add(trace.header)
        data.addAll(trace.stackFrameItems.map { it.toString() })
        vertex.setListData(data.toTypedArray())
        val lastStackFrame = data.getOrNull(1)

        vertex.border = if (lastRunningStackFrame == lastStackFrame) {
            createRoundedBorder(JBColor.BLUE)
        } else {
            createRoundedBorder(JBColor.BLACK)
        }

        return vertex
    }

    private fun setupUI() {
        val debugProcess = suspendContextImpl.debugProcess
        val activeExecutionStack = suspendContextImpl.activeExecutionStack

        vertex.cellRenderer = CoroutineStacksPanel.CustomCellRenderer(trace.coroutinesActiveLabel)
        vertex.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                val list = e?.source as? JBList<*> ?: return
                val index = list.locationToIndex(e.point).takeIf { it > 0 } ?: return
                val stackFrameItem = trace.stackFrameItems[index - 1]

                stackFrameItem?.let { frameItem ->
                    val frame = frameItem.createFrame(debugProcess)

                    if (activeExecutionStack != null && frame != null) {
                        suspendContextImpl.setCurrentStackFrame(activeExecutionStack, frame)
                    }
                }
            }
        })
    }
}

private fun SuspendContextImpl.setCurrentStackFrame(executionStack: XExecutionStack?, stackFrame: XStackFrame) {
    val fileToNavigate = stackFrame.sourcePosition?.file ?: return
    val session = debugProcess.session.xDebugSession ?: return
    if (!ClsClassFinder.isKotlinInternalCompiledFile(fileToNavigate)) {
        ApplicationManager.getApplication().invokeLater {
            if (executionStack != null) {
                session.setCurrentStackFrame(executionStack, stackFrame, false)
            }
        }
    }
}

private fun SuspendContextImpl.buildStackFrameGraph(
    coroutineDataList: List<CoroutineInfoData>,
    rootValue: Node
): String {
    var lastRunningStackFrame = ""

    coroutineDataList.forEach { coroutineData ->
        var currentNode = rootValue

        val coroutineFrameItemLists = CoroutineFrameBuilder.build(coroutineData, this)
        coroutineFrameItemLists?.frames?.reversed()?.forEach { stackFrame ->
            val location = stackFrame.location
            val child = currentNode.children[location]

            if (child != null) {
                if (coroutineData.descriptor.state == State.RUNNING) {
                    lastRunningStackFrame = child.stackFrameItem.toString()
                }

                child.num++
                child.coroutinesActive += coroutineData.render()
                currentNode = child
            } else {
                if (coroutineData.descriptor.state == State.RUNNING) {
                    lastRunningStackFrame = location.toString()
                }

                val node = Node(
                    stackFrame,
                    1,
                    mutableMapOf(),
                    coroutineData.render()
                )
                currentNode.children[location] = node
                currentNode = node
            }
        }
    }
    return lastRunningStackFrame
}