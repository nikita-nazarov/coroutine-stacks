package com.nikitanazarov.coroutinestacks

import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.JBUI
import com.intellij.xdebugger.XDebuggerManager
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutine.data.State
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutineDebugProbesProxy
import java.awt.*
import javax.swing.*
import javax.swing.border.Border

class CoroutineStacksPanel(project: Project) : JBPanelWithEmptyText() {
    private val panelContent = Box.createVerticalBox()
    private val forest = Box.createVerticalBox()

    class CustomCellRenderer(
        private val coroutinesActive: String?,
        padding: Int = 3
    ) : DefaultListCellRenderer() {
        private val itemBorder = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.GRAY)
        private val leftPaddingBorder: Border = JBUI.Borders.emptyLeft(padding)
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
                } else if (index < listSize) {
                    toolTipText = value.toString()
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
}
