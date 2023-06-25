package com.nikitanazarov.coroutinestacks

import com.intellij.debugger.PositionManager
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.*
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.nikitanazarov.coroutinestacks.ui.ContainerWithEdges
import com.nikitanazarov.coroutinestacks.ui.ForestLayout
import com.nikitanazarov.coroutinestacks.ui.Separator
import com.sun.jdi.Location
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutine.data.State
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutineDebugProbesProxy
import java.awt.Color
import java.awt.Dimension
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*

// Currently, the code is not looking good, pushing code only to save progress
// make necessary changes in the next commit
class CoroutineStacksPanel(project: Project) : JBPanelWithEmptyText() {
    private val coroutineGraph = Box.createVerticalBox()
    private val forest = Box.createVerticalBox()

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
            }

            if (index < list.model.size - 1) {
                (renderer as? JComponent)?.border = itemBorder
            } else {
                (renderer as? JComponent)?.border = null
            }

            return renderer
        }
    }

    fun performPositionTask(positionManager: PositionManager, location: Location) {
        object : SwingWorker<SourcePosition?, Void>() {
            override fun doInBackground(): SourcePosition? {
                return positionManager.getSourcePosition(location)
            }

            override fun done() {
                val sourcePosition = get()
                sourcePosition?.navigate(true)
            }
        }.execute()
    }

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

        val positionManager = suspendContextImpl.debugProcess.positionManager
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
            println("yump: ${it.actionCommand}")
            val selectedDispatcher = dispatcherDropdownMenu.selectedItem as String
            updateParallelStackTree(selectedDispatcher, dispatcherToCoroutineDataList, positionManager)
        }

        val comboBoxSize = Dimension(Constants.comboBoxHeight, Constants.comboBoxWidth)
        dispatcherDropdownMenu.preferredSize = comboBoxSize
        dispatcherDropdownMenu.maximumSize = comboBoxSize
        dispatcherDropdownMenu.minimumSize = comboBoxSize
        coroutineStacksWindowHeader.add(dispatcherLabel)
        coroutineStacksWindowHeader.add(dispatcherDropdownMenu)

        val selectedDispatcher = dispatcherDropdownMenu.selectedItem as String
        updateParallelStackTree(selectedDispatcher, dispatcherToCoroutineDataList, positionManager)
        coroutineGraph.add(coroutineStacksWindowHeader)
        coroutineGraph.add(forest)
        add(coroutineGraph)
    }

    private fun updateParallelStackTree(
        selectedDispatcher: String,
        dispatcherToCoroutineDataList: MutableMap<String, MutableList<CoroutineInfoData>>,
        positionManager: CompoundPositionManager
    ) {
        println("a1")
        forest.removeAll()
        val root = Node(null, -1, mutableMapOf(), "")
        val coroutineStackForest = generateParallelStackTree(
            root,
            dispatcherToCoroutineDataList[selectedDispatcher]!!,
            positionManager
        )
        forest.add(coroutineStackForest)
        println("b1")
    }

    private fun generateParallelStackTree(
        rootValue: Node,
        coroutineDataList: MutableList<CoroutineInfoData>,
        positionManager: CompoundPositionManager
    ): JBScrollPane {
        var activeVertex = ""
        coroutineDataList.forEach { coroutineData ->
            var currentNode = rootValue
            coroutineData.stackTrace.reversed().forEach { stackFrame ->
                val location = stackFrame.location
                val child = currentNode.children[location]

                if (child != null) {
                    if (coroutineData.descriptor.state == State.RUNNING) {
                        activeVertex = child.stackFrameLocation.toString()
                    }

                    child.num++
                    child.coroutinesActive += "${coroutineData.descriptor.name}${coroutineData.descriptor.id} ${coroutineData.descriptor.state} \n"
                    currentNode = child
                } else {
                    if (coroutineData.descriptor.state == State.RUNNING) {
                        activeVertex = location.toString()
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

        val locationMatrix: MutableList<MutableList<Location?>> = mutableListOf()
        val stack = Stack<Pair<Node, Int>>()
        val parentStack = Stack<Node>() // Stack to track parent nodes
        val headerContent: MutableList<String> = mutableListOf()
        val hoverContentForHeaders: MutableList<String?> = mutableListOf()

        stack.push(Pair(rootValue, 0))
        val locationsFollowedBySeparators = mutableListOf<Location?>()
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

            // Add children to the stack in reverse order with an incremented level
            currentNode.children.values.reversed().forEachIndexed { index, child ->
                if (index != currentNode.children.values.size - 1) {
                    locationsFollowedBySeparators.add(child.stackFrameLocation)
                }
                stack.push(child to (level + 1))
                parentStack.push(currentNode)
            }
        }

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
        locationMatrixWithSeparators.forEachIndexed { i, separatorOrLocation ->
            if (separatorOrLocation == separator) {
                componentData.add(Separator())
            } else {
                val vertex = JBList<String>()
                val data = mutableListOf<String>()
                data.add(headerContent[headerIndex])
                data.addAll(locationMatrixWithSeparators[i].map { it.toString() })
                vertex.setListData(data.toTypedArray())
                vertex.border = if (activeVertex == data[locationMatrixWithSeparators[i].size]) {
                    BorderFactory.createLineBorder(Color(5, 201, 255))
                } else {
                    BorderFactory.createLineBorder(Color.BLACK)
                }
                vertex.cellRenderer = CustomCellRenderer(hoverContentForHeaders[headerIndex])
                vertex.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        val list = e?.source as JBList<*>
                        val index = list.locationToIndex(e.point)
                        println("index: $index")
                        val location = if (index > 0) locationMatrixWithSeparators[i][index - 1] else null
                        if (location != null) {
                            performPositionTask(positionManager, location)
                        }
                    }
                })
                componentData.add(vertex)
                headerIndex += 1
            }
        }
        val forest = ContainerWithEdges()
        componentData.forEach { forest.add(it) }
        forest.layout = ForestLayout()
        return JBScrollPane(forest)
    }
}