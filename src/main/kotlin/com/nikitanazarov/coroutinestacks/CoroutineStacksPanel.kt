package com.nikitanazarov.coroutinestacks

import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.util.preferredWidth
import com.intellij.xdebugger.XDebuggerManager
import com.nikitanazarov.coroutinestacks.ui.ContainerWithEdges
import com.nikitanazarov.coroutinestacks.ui.ForestLayout
import com.nikitanazarov.coroutinestacks.ui.Separator
import com.sun.jdi.Location
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutine.data.State
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutineDebugProbesProxy
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*

class CoroutineStacksPanel(project: Project) : JBPanelWithEmptyText() {
    private val panelContent = Box.createVerticalBox()
    private val forest = Box.createVerticalBox()

    data class CoroutineTrace(val locations: MutableList<Location?>, val header: String, val hoverContent: String)

    data class Node(
        val stackFrameLocation: Location?,
        var num: Int, // Represents how many coroutines have this frame in their stack trace
        val children: MutableMap<Location, Node>,
        var coroutinesActive: String
    )

    class CustomCellRenderer(
        private val coroutinesActive: String?,
        private val averagePreferredWidth: Int
    ) : DefaultListCellRenderer() {
        private val itemBorder = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.GRAY)

        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val renderer = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (renderer !is JComponent) {
                return renderer
            }

            with(renderer) {
                val listSize = list.model.size
                if (index == 0) {
                    toolTipText = coroutinesActive
                    font = renderer.font.deriveFont(Font.BOLD)
                    preferredWidth = averagePreferredWidth
                } else if (index < listSize) {
                    toolTipText = value.toString()
                    preferredWidth = averagePreferredWidth
                }
                border = if (index < listSize - 1) itemBorder else null
            }

            return renderer
        }
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        emptyText.text = CoroutineStacksBundle.message("no.java.debug.process.is.running")
        val currentSession = XDebuggerManager.getInstance(project).currentSession
        if (currentSession != null) {
            val currentProcess = (currentSession.debugProcess as JavaDebugProcess).debuggerSession.process
            emptyText.component.isVisible = false
            currentProcess.managerThread.invoke(object : DebuggerCommandImpl() {
                override fun action() {
                    buildCoroutineGraph(currentProcess.suspendManager.pausedContext)
                }
            })

            currentProcess.addDebugProcessListener(createPanelBuilderListener())
        }

        project.messageBus.connect()
            .subscribe<DebuggerManagerListener>(DebuggerManagerListener.TOPIC, object : DebuggerManagerListener {
                override fun sessionAttached(session: DebuggerSession?) {
                    emptyText.text = CoroutineStacksBundle.message("should.be.stopped.on.a.breakpoint")
                }

                override fun sessionCreated(session: DebuggerSession) {
                    session.process.addDebugProcessListener(createPanelBuilderListener())
                }

                override fun sessionRemoved(session: DebuggerSession) {
                    emptyText.text = CoroutineStacksBundle.message("no.java.debug.process.is.running")
                    emptyText.component.isVisible = true
                    panelContent.removeAll()
                }
            })
    }


    private fun createPanelBuilderListener() = object : DebugProcessListener {
        override fun paused(suspendContext: SuspendContext) {
            emptyText.component.isVisible = false
            buildCoroutineGraph(suspendContext)
        }

        override fun resumed(suspendContext: SuspendContext?) {
            panelContent.removeAll()
        }
    }

    fun buildCoroutineGraph(suspendContext: SuspendContext) {
        val suspendContextImpl = suspendContext as? SuspendContextImpl ?: run {
            emptyText.text = CoroutineStacksBundle.message("coroutine.stacks.could.not.be.built")
            return
        }

        val coroutineInfoCache = CoroutineDebugProbesProxy(suspendContextImpl).dumpCoroutines()
        val coroutineInfoDataList = coroutineInfoCache.cache
        val dispatcherToCoroutineDataList = mutableMapOf<String, MutableList<CoroutineInfoData>>()
        for (info in coroutineInfoDataList) {
            val dispatcher = info.descriptor.dispatcher ?: continue
            dispatcherToCoroutineDataList.computeIfAbsent(dispatcher) { mutableListOf() }.add(info)
        }

        val dispatcherLabel = JLabel(CoroutineStacksBundle.message("select.dispatcher"))
        val coroutineStacksWindowHeader = Box.createHorizontalBox()
        val dispatchers = dispatcherToCoroutineDataList.keys.toTypedArray()
        val dispatcherDropdownMenu = ComboBox(dispatchers)

        dispatcherDropdownMenu.addActionListener {
            val selectedDispatcher = dispatcherDropdownMenu.selectedItem as String
            val coroutineDataList = dispatcherToCoroutineDataList[selectedDispatcher]
            if (coroutineDataList != null) {
                updateCoroutineStackForest(coroutineDataList, suspendContextImpl)
            }
        }

        val comboBoxSize = Dimension(Constants.comboBoxHeight, Constants.comboBoxWidth)
        dispatcherDropdownMenu.apply {
            preferredSize = comboBoxSize
            maximumSize = comboBoxSize
            minimumSize = comboBoxSize
        }
        coroutineStacksWindowHeader.add(dispatcherLabel)
        coroutineStacksWindowHeader.add(dispatcherDropdownMenu)

        dispatcherToCoroutineDataList.forEach { (dispatcher, coroutineDataList) ->
            coroutineDataList.forEach { data ->
                if (data.descriptor.state == State.RUNNING) {
                    dispatcherDropdownMenu.selectedItem = dispatcher
                }
            }
        }

        val selectedDispatcher = dispatcherDropdownMenu.selectedItem as String
        val coroutineDataList = dispatcherToCoroutineDataList[selectedDispatcher]
        if (coroutineDataList != null) {
            updateCoroutineStackForest(coroutineDataList, suspendContextImpl)
        }
        panelContent.add(coroutineStacksWindowHeader)
        panelContent.add(forest)
        add(panelContent)
    }

    private fun updateCoroutineStackForest(
        coroutineDataList: MutableList<CoroutineInfoData>,
        suspendContextImpl: SuspendContextImpl
    ) {
        forest.removeAll()
        val root = Node(null, -1, mutableMapOf(), "")
        val coroutineStackForest = suspendContextImpl.buildCoroutineStackForest(
            root,
            coroutineDataList
        )
        forest.add(coroutineStackForest)
        updateUI()
    }

    private fun SuspendContextImpl.buildCoroutineStackForest(
        rootValue: Node,
        coroutineDataList: MutableList<CoroutineInfoData>,
    ): JBScrollPane {
        val lastRunningStackFrame = buildStackFrameGraph(coroutineDataList, rootValue)
        val coroutineTraces = createCoroutineTraces(rootValue)
        return createCoroutineTraceForest(coroutineTraces, lastRunningStackFrame)
    }

    private fun SuspendContextImpl.createCoroutineTraceForest(traces: List<CoroutineTrace?>, lastRunningStackFrame: String): JBScrollPane {
        val componentData: MutableList<Component> = mutableListOf()
        var previousListSelection: JBList<*>? = null
        val averagePreferredWidth = calculateAveragePreferredWidth(traces)
        traces.forEach { trace ->
            if (trace == null) {
                componentData.add(Separator())
                return@forEach
            }
            val vertex = createCoroutineTraceUI(trace, lastRunningStackFrame, averagePreferredWidth)
            vertex.addListSelectionListener { e ->
                val currentList = e.source as? JBList<*> ?: return@addListSelectionListener
                if (previousListSelection != currentList) {
                    previousListSelection?.clearSelection()
                }
                previousListSelection = currentList
            }
            componentData.add(vertex)
        }

        val forest = ContainerWithEdges()
        componentData.forEach { forest.add(it) }
        forest.layout = ForestLayout()
        return JBScrollPane(forest)
    }

    private fun calculateAveragePreferredWidth(traces: List<CoroutineTrace?>): Int {
        val validTraces = traces.filterNotNull()
        val maxWidth = validTraces.sumOf { trace ->
            val vertex = createVertexAndData(trace).first
            vertex.preferredWidth
        }
        return maxWidth / validTraces.size
    }

    private fun createCoroutineTraces(rootValue: Node): List<CoroutineTrace?> {
        val stack = Stack<Pair<Node, Int>>()
        val parentStack = Stack<Node>()

        stack.push(Pair(rootValue, 0))
        val locationsFollowedBySeparators = mutableListOf<Location?>()

        var currentTrace: CoroutineTrace? = null
        val coroutineTraces = mutableListOf<CoroutineTrace?>()
        while (stack.isNotEmpty()) {
            val (currentNode, level) = stack.pop()
            val parent = if (parentStack.isNotEmpty()) parentStack.pop() else null

            if (parent != null) {
                if (locationsFollowedBySeparators.contains(currentNode.stackFrameLocation)) {
                    coroutineTraces.add(null)
                }
                if (parent.num != currentNode.num) {
                    currentTrace = CoroutineTrace(
                        mutableListOf(currentNode.stackFrameLocation),
                        CoroutineStacksBundle.message("number.of.coroutines.active", currentNode.num),
                        currentNode.coroutinesActive
                    )
                    coroutineTraces.add(currentTrace)
                } else {
                    currentTrace?.locations?.add(currentNode.stackFrameLocation)
                }
            }

            currentNode.children.values.reversed().forEachIndexed { index, child ->
                if (index != currentNode.children.values.size - 1) {
                    locationsFollowedBySeparators.add(child.stackFrameLocation)
                }
                stack.push(child to (level + 1))
                parentStack.push(currentNode)
            }
        }

        return coroutineTraces
    }

    private fun SuspendContextImpl.createCoroutineTraceUI(
        trace: CoroutineTrace,
        lastRunningStackFrame: String,
        averagePreferredWidth: Int
    ): JBList<String> {
        val (vertex, data) = createVertexAndData(trace)
        val lastStackFrame = data[trace.locations.size]
        vertex.border = if (lastRunningStackFrame == lastStackFrame) {
            BorderFactory.createLineBorder(JBColor.BLUE)
        } else {
            BorderFactory.createLineBorder(JBColor.BLACK)
        }
        vertex.cellRenderer = CustomCellRenderer(trace.hoverContent, averagePreferredWidth)
        vertex.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                val list = e?.source as? JBList<*> ?: return
                val index = list.locationToIndex(e.point).takeIf { it > 0 } ?: return
                val location = trace.locations[index - 1]
                debugProcess.managerThread.invoke(object : DebuggerCommandImpl() {
                    override fun action() {
                        val positionManager = debugProcess.positionManager
                        val sourcePosition = positionManager.getSourcePosition(location)
                        sourcePosition?.navigate(true)
                    }
                })
            }
        })

        return vertex
    }

    private fun createVertexAndData(trace: CoroutineTrace): Pair<JBList<String>, MutableList<String>> {
        val vertex = JBList<String>()
        val data = mutableListOf<String>()
        data.add(trace.header)
        data.addAll(trace.locations.map { it.toString() })
        vertex.setListData(data.toTypedArray())
        return Pair(vertex, data)
    }

    private fun buildStackFrameGraph(
        coroutineDataList: MutableList<CoroutineInfoData>,
        rootValue: Node
    ): String {
        var lastRunningStackFrame = ""
        coroutineDataList.forEach { coroutineData ->
            var currentNode = rootValue
            coroutineData.stackTrace.reversed().forEach { stackFrame ->
                val location = stackFrame.location
                val child = currentNode.children[location]

                if (child != null) {
                    if (coroutineData.descriptor.state == State.RUNNING) {
                        lastRunningStackFrame = child.stackFrameLocation.toString()
                    }

                    child.num++
                    child.coroutinesActive += "${coroutineData.descriptor.name}${coroutineData.descriptor.id} ${coroutineData.descriptor.state} \n"
                    currentNode = child
                } else {
                    if (coroutineData.descriptor.state == State.RUNNING) {
                        lastRunningStackFrame = location.toString()
                    }

                    val node = Node(
                        location,
                        1,
                        mutableMapOf(),
                        "${coroutineData.descriptor.name}${coroutineData.descriptor.id} ${coroutineData.descriptor.state} \n"
                    )
                    currentNode.children[location] = node
                    currentNode = node
                }
            }
        }
        return lastRunningStackFrame
    }
}