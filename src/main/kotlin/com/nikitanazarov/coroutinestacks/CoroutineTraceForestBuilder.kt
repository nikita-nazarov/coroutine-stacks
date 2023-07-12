package com.nikitanazarov.coroutinestacks

import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.util.preferredWidth
import com.nikitanazarov.coroutinestacks.ui.*
import com.sun.jdi.Location
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutine.data.State
import org.jetbrains.kotlin.idea.debugger.coroutine.util.CoroutineFrameBuilder
import java.awt.Component
import java.util.*

data class Node(
    val stackFrameItem: CoroutineStackFrameItem? = null,
    var num: Int = 0, // Represents how many coroutines have this frame in their stack trace
    val children: MutableMap<Location, Node> = mutableMapOf(),
    var coroutinesActive: String = ""
)

data class CoroutineTrace(val stackFrameItems: MutableList<CoroutineStackFrameItem?>,
                          val header: String,
                          val coroutinesActiveLabel: String)

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
    if (traces.isEmpty()) {
        return null
    }
    val vertexData = mutableListOf<JBList<String>?>()
    val componentData = mutableListOf<Component>()
    var previousListSelection: JBList<*>? = null
    var maxWidth = 0
    var traceNotNullCount = 0

    traces.forEach { trace ->
        if (trace == null) {
            vertexData.add(null)
            return@forEach
        }

        val vertex = CoroutineFramesList(this, trace, lastRunningStackFrame)
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

    if (traceNotNullCount == 0) {
        return null
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
    val panel = DraggablePanel()
    panel.add(forest)

    return JBScrollPane(panel)
}

private fun createCoroutineTraces(rootValue: Node): List<CoroutineTrace?> {
    val stack = Stack<Pair<Node, Int>>().apply { push(rootValue to 0) }
    val parentStack = Stack<Node>()
    var previousLevel: Int? = null
    val coroutineTraces = mutableListOf<CoroutineTrace?>()

    while (stack.isNotEmpty()) {
        val (currentNode, currentLevel) = stack.pop()
        val parent = if (parentStack.isNotEmpty()) parentStack.pop() else null
        var coroutineStackHeader = CoroutineStacksBundle.message("number.of.coroutine")

        if (parent != null && parent.num != currentNode.num) {
            if (currentNode.num > 1)
                coroutineStackHeader = CoroutineStacksBundle.message("number.of.coroutines", currentNode.num)
            val currentTrace = CoroutineTrace(
                mutableListOf(currentNode.stackFrameItem),
                coroutineStackHeader,
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

private fun SuspendContextImpl.buildStackFrameGraph(
    coroutineDataList: List<CoroutineInfoData>,
    rootValue: Node
): String {
    var lastRunningStackFrame = ""

    coroutineDataList.forEach { coroutineData ->
        var currentNode = rootValue

        val coroutineFrameItemLists = CoroutineFrameBuilder.build(coroutineData, this) ?: return@forEach
        coroutineFrameItemLists.frames.reversed().forEach { stackFrame ->
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

private fun CoroutineInfoData.render(): String =
    "${descriptor.name}${descriptor.id} ${descriptor.state}\n"