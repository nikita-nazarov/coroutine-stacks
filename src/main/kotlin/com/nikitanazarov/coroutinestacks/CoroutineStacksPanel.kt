package com.nikitanazarov.coroutinestacks

import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.util.preferredWidth
import com.intellij.xdebugger.XDebuggerManager
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
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutineDebugProbesProxy
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

class CoroutineStacksPanel(project: Project) : JBPanelWithEmptyText() {
    private val panelContent = Box.createVerticalBox()
    private val forest = Box.createVerticalBox()

    data class CoroutineTrace(val stackFrameItems: MutableList<CoroutineStackFrameItem?>, val header: String, val hoverContent: String)

    data class Node(
        val stackFrameItem: CoroutineStackFrameItem? = null,
        var num: Int = 0, // Represents how many coroutines have this frame in their stack trace
        val children: MutableMap<Location, Node> = mutableMapOf(),
        var coroutinesActive: String = ""
    )

    class CustomCellRenderer(
        private val coroutinesActive: String?,
        private val averagePreferredWidth: Int,
        padding: Int = 3
    ) : DefaultListCellRenderer() {
        private val itemBorder = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.GRAY)
        private val leftPaddingBorder: Border = EmptyBorder(0, padding, 0, 0)
        private val compoundBorder = BorderFactory.createCompoundBorder(itemBorder, leftPaddingBorder)

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
                    font = font.deriveFont(Font.BOLD)
                    preferredWidth = averagePreferredWidth
                } else if (index < listSize) {
                    toolTipText = value.toString()
                    preferredWidth = averagePreferredWidth
                }
                border = when {
                    index < listSize - 1 -> compoundBorder
                    index == listSize - 1 -> leftPaddingBorder
                    else -> null
                }
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

    private fun createRoundedBorder(color: JBColor): Border {
        val cornerRadius = 10
        val borderThickness = 1

        val roundedBorder = object : LineBorder(color, borderThickness) {
            override fun getBorderInsets(c: Component?): Insets {
                val insets = super.getBorderInsets(c)
                return Insets(insets.top, insets.left, insets.bottom, insets.right)
            }

            override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
                val g2d = g as? Graphics2D
                g2d?.let {
                    val arc = 2 * cornerRadius
                    it.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    it.color = color
                    it.drawRoundRect(x, y, width - 1, height - 1, arc, arc)
                }
            }
        }

        return roundedBorder
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
            if (!coroutineDataList.isNullOrEmpty()) {
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
        if (!coroutineDataList.isNullOrEmpty()) {
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
        val root = Node()
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

    private fun SuspendContextImpl.createCoroutineTraceUI(
        trace: CoroutineTrace,
        lastRunningStackFrame: String,
        averagePreferredWidth: Int
    ): JBList<String> {
        val (vertex, data) = createVertexAndData(trace)
        val lastStackFrame = data[1]

        val border = if (lastRunningStackFrame == lastStackFrame) {
            createRoundedBorder(JBColor.BLUE)
        } else {
            createRoundedBorder(JBColor.BLACK)
        }
        vertex.border = border

        vertex.cellRenderer = CustomCellRenderer(trace.hoverContent, averagePreferredWidth)
        vertex.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                val list = e?.source as? JBList<*> ?: return
                val index = list.locationToIndex(e.point).takeIf { it > 0 } ?: return
                val stackFrameItem = trace.stackFrameItems[index - 1]

                stackFrameItem?.let { frameItem ->
                    val frame = frameItem.createFrame(debugProcess)

                    if (activeExecutionStack != null && frame != null) {
                        setCurrentStackFrame(activeExecutionStack, frame)
                    }
                }

            }
        })

        return vertex
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

    private fun createVertexAndData(trace: CoroutineTrace): Pair<JBList<String>, MutableList<String>> {
        val vertex = JBList<String>()
        val data = mutableListOf<String>()
        data.add(trace.header)
        data.addAll(trace.stackFrameItems.map { it.toString() })
        vertex.setListData(data.toTypedArray())
        return Pair(vertex, data)
    }

    private fun buildStackFrameGraph(
        coroutineDataList: List<CoroutineInfoData>,
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
                        lastRunningStackFrame = child.stackFrameItem.toString()
                    }

                    child.num++
                    child.coroutinesActive += "${coroutineData.descriptor.name}${coroutineData.descriptor.id} ${coroutineData.descriptor.state}\n"
                    currentNode = child
                } else {
                    if (coroutineData.descriptor.state == State.RUNNING) {
                        lastRunningStackFrame = location.toString()
                    }

                    val node = Node(
                        stackFrame,
                        1,
                        mutableMapOf(),
                        "${coroutineData.descriptor.name}${coroutineData.descriptor.id} ${coroutineData.descriptor.state}\n"
                    )
                    currentNode.children[location] = node
                    currentNode = node
                }
            }
        }
        return lastRunningStackFrame
    }
}