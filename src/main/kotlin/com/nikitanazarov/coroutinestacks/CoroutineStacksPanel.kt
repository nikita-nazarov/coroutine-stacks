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
import com.intellij.xdebugger.XDebuggerManager
import com.nikitanazarov.coroutinestacks.ui.ContainerWithEdges
import com.nikitanazarov.coroutinestacks.ui.ForestLayout
import com.nikitanazarov.coroutinestacks.ui.Separator
import com.sun.jdi.Location
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutine.data.State
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutineDebugProbesProxy
import java.awt.Color
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

    // num represents the number of times stackframe represented by respective Node occurs in the collection of all stack frames
    // of all stack traces
    data class Node(
        val stackFrameLocation: Location?,
        var num: Int,
        val children: MutableMap<Location, Node>,
        var coroutinesActive: String
    )

    class CustomCellRenderer(
        private val coroutinesActive: String?
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
            if (index == 0) {
                (renderer as? JComponent)?.apply {
                    toolTipText = coroutinesActive
                    font = renderer.font.deriveFont(Font.BOLD)
                }
            } else if (index < list.model.size) {
                (renderer as? JComponent)?.apply {
                    toolTipText = value.toString()
                }
            }

            (renderer as? JComponent)?.border = if (index < list.model.size - 1) itemBorder else null
            return renderer
        }
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        emptyText.text = CoroutineStacksBundle.message("no.java.debug.process.is.running")
        val currentSession = XDebuggerManager.getInstance(project).currentSession
        if (currentSession != null) {
            val myProcess = (currentSession.debugProcess as JavaDebugProcess).debuggerSession.process
            emptyText.component.isVisible = false
            myProcess.managerThread.invoke(object : DebuggerCommandImpl() {
                override fun action() {
                    buildCoroutineGraph(myProcess.suspendManager.pausedContext)
                }
            })

            myProcess.addDebugProcessListener(debugProcessListener())
        }

        project.messageBus.connect()
            .subscribe<DebuggerManagerListener>(DebuggerManagerListener.TOPIC, object : DebuggerManagerListener {
                override fun sessionAttached(session: DebuggerSession?) {
                    emptyText.text = CoroutineStacksBundle.message("should.be.stopped.on.a.breakpoint")
                }

                override fun sessionCreated(session: DebuggerSession) {
                    session.process.addDebugProcessListener(debugProcessListener())
                }

                override fun sessionRemoved(session: DebuggerSession) {
                    emptyText.text = CoroutineStacksBundle.message("no.java.debug.process.is.running")
                    emptyText.component.isVisible = true
                    panelContent.removeAll()
                }
            })
    }

    private fun debugProcessListener() = object : DebugProcessListener {
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
            updateCoroutineStackForest(selectedDispatcher, dispatcherToCoroutineDataList, suspendContextImpl)
        }

        val comboBoxSize = Dimension(Constants.comboBoxHeight, Constants.comboBoxWidth)
        dispatcherDropdownMenu.apply {
            preferredSize = comboBoxSize
            maximumSize = comboBoxSize
            minimumSize = comboBoxSize
        }
        coroutineStacksWindowHeader.add(dispatcherLabel)
        coroutineStacksWindowHeader.add(dispatcherDropdownMenu)

        val selectedDispatcher = dispatcherDropdownMenu.selectedItem as String
        updateCoroutineStackForest(selectedDispatcher, dispatcherToCoroutineDataList, suspendContextImpl)
        panelContent.add(coroutineStacksWindowHeader)
        panelContent.add(forest)
        add(panelContent)
    }

    private fun updateCoroutineStackForest(
        selectedDispatcher: String,
        dispatcherToCoroutineDataList: MutableMap<String, MutableList<CoroutineInfoData>>,
        suspendContextImpl: SuspendContextImpl
    ) {
        forest.removeAll()
        val root = Node(null, -1, mutableMapOf(), "")
        val coroutineStackForest = buildCoroutineStackForest(
            root,
            dispatcherToCoroutineDataList[selectedDispatcher]!!,
            suspendContextImpl
        )
        forest.add(coroutineStackForest)
        updateUI()
    }

    private fun buildCoroutineStackForest(
        rootValue: Node,
        coroutineDataList: MutableList<CoroutineInfoData>,
        suspendContextImpl: SuspendContextImpl
    ): JBScrollPane {
        var lastRunningStackFrame = ""
        lastRunningStackFrame = buildStackFrameGraph(coroutineDataList, rootValue, lastRunningStackFrame)

        val locationMatrix: MutableList<MutableList<Location?>> = mutableListOf()
        val stack = Stack<Pair<Node, Int>>()
        val parentStack = Stack<Node>()
        val headerContent: MutableList<String> = mutableListOf()
        val hoverContentForHeaders: MutableList<String?> = mutableListOf()

        stack.push(Pair(rootValue, 0))
        val locationsFollowedBySeparators = mutableListOf<Location?>()

        fillLocationMatrix(
            stack,
            parentStack,
            headerContent,
            hoverContentForHeaders,
            locationMatrix,
            locationsFollowedBySeparators
        )

        val separator : MutableList<Location?> = mutableListOf(null)
        val locationMatrixWithSeparators: MutableList<MutableList<Location?>> = mutableListOf()
        for (locationData in locationMatrix) {
            if (locationsFollowedBySeparators.contains(locationData[0])) {
                locationMatrixWithSeparators.add(separator)
            }
            locationMatrixWithSeparators.add(locationData)
        }

        val separatorCount = locationMatrix.size - locationsFollowedBySeparators.size
        repeat(separatorCount) {
            locationMatrixWithSeparators.add(separator)
        }

        val componentData: MutableList<Component> = mutableListOf()
        var headerIndex = 0
        var previousListSelection: JBList<String>? = null
        locationMatrixWithSeparators.forEachIndexed { i, separatorOrLocation ->
            if (separatorOrLocation === separator) {
                componentData.add(Separator())
            } else {
                val vertex = createCoroutineTrace(
                    headerContent,
                    headerIndex,
                    locationMatrixWithSeparators,
                    i,
                    lastRunningStackFrame,
                    hoverContentForHeaders,
                    suspendContextImpl
                )
                vertex.addListSelectionListener { e ->
                    val currentList = e.source as JBList<String>
                    if (previousListSelection != null && previousListSelection != currentList) {
                        previousListSelection?.clearSelection()
                    }
                    previousListSelection = currentList
                }
                componentData.add(vertex)
                headerIndex += 1
            }
        }

        val forest = ContainerWithEdges()
        componentData.forEach { forest.add(it) }
        forest.layout = ForestLayout()
        return JBScrollPane(forest)
    }

    private fun fillLocationMatrix(
        stack: Stack<Pair<Node, Int>>,
        parentStack: Stack<Node>,
        headerContent: MutableList<String>,
        hoverContentForHeaders: MutableList<String?>,
        locationMatrix: MutableList<MutableList<Location?>>,
        locationsFollowedBySeparators: MutableList<Location?>
    ) {
        var locationMatrixPointer = -1
        while (stack.isNotEmpty()) {
            val (currentNode, level) = stack.pop()
            val parent = if (parentStack.isNotEmpty()) parentStack.pop() else null

            if (parent != null) {
                if (parent.num != currentNode.num) {
                    headerContent.add("${currentNode.num} Coroutines ACTIVE")
                    hoverContentForHeaders.add(currentNode.coroutinesActive)
                    locationMatrix.add(mutableListOf(currentNode.stackFrameLocation))
                    locationMatrixPointer += 1
                } else {
                    locationMatrix[locationMatrixPointer].add(currentNode.stackFrameLocation)
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
    }

    private fun createCoroutineTrace(
        headerContent: MutableList<String>,
        headerIndex: Int,
        locationMatrixWithSeparators: MutableList<MutableList<Location?>>,
        i: Int,
        lastRunningStackFrame: String,
        hoverContentForHeaders: MutableList<String?>,
        suspendContextImpl: SuspendContextImpl
    ): JBList<String> {
        val vertex = JBList<String>()
        val data = mutableListOf<String>()
        data.add(headerContent[headerIndex])
        data.addAll(locationMatrixWithSeparators[i].map { it.toString() })
        vertex.setListData(data.toTypedArray())
        val lastStackFrame = data[locationMatrixWithSeparators[i].size]
        vertex.border = if (lastRunningStackFrame == lastStackFrame) {
            BorderFactory.createLineBorder(Color(5, 201, 255))
        } else {
            BorderFactory.createLineBorder(Color.BLACK)
        }
        vertex.cellRenderer = CustomCellRenderer(hoverContentForHeaders[headerIndex])
        vertex.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                val list = e?.source as JBList<*>
                val index = list.locationToIndex(e.point)
                val location = if (index > 0) locationMatrixWithSeparators[i][index - 1] else null
                if (location != null) {
                    suspendContextImpl.debugProcess.managerThread.invoke(object : DebuggerCommandImpl() {
                        override fun action() {
                            val positionManager = suspendContextImpl.debugProcess.positionManager
                            val sourcePosition = positionManager.getSourcePosition(location)
                            sourcePosition?.navigate(true)
                        }
                    })
                }
            }
        })
        return vertex
    }

    private fun buildStackFrameGraph(
        coroutineDataList: MutableList<CoroutineInfoData>,
        rootValue: Node,
        lastRunningStackFrame: String
    ): String {
        var lastRunningStackFrame1 = lastRunningStackFrame
        coroutineDataList.forEach { coroutineData ->
            var currentNode = rootValue
            coroutineData.stackTrace.reversed().forEach { stackFrame ->
                val location = stackFrame.location
                val child = currentNode.children[location]

                if (child != null) {
                    if (coroutineData.descriptor.state == State.RUNNING) {
                        lastRunningStackFrame1 = child.stackFrameLocation.toString()
                    }

                    child.num++
                    child.coroutinesActive += "${coroutineData.descriptor.name}${coroutineData.descriptor.id} ${coroutineData.descriptor.state} \n"
                    currentNode = child
                } else {
                    if (coroutineData.descriptor.state == State.RUNNING) {
                        lastRunningStackFrame1 = location.toString()
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
        return lastRunningStackFrame1
    }
}