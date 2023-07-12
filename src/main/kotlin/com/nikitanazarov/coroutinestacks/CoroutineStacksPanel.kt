package com.nikitanazarov.coroutinestacks

import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.xdebugger.XDebuggerManager
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutine.data.State
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutineDebugProbesProxy
import java.awt.*
import javax.swing.*

class CoroutineStacksPanel(project: Project) : JBPanelWithEmptyText() {
    private val panelContent = Box.createVerticalBox()
    private val forest = Box.createVerticalBox()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        emptyText.text = CoroutineStacksBundle.message("no.java.debug.process.is.running")
        val currentSession = XDebuggerManager.getInstance(project).currentSession
        if (currentSession != null) {
            val javaDebugProcess = currentSession.debugProcess as? JavaDebugProcess
            val currentProcess = javaDebugProcess?.debuggerSession?.process
            emptyText.component.isVisible = false
            currentProcess?.managerThread?.invoke(object : DebuggerCommandImpl() {
                override fun action() {
                    buildCoroutineGraph(currentProcess.suspendManager.pausedContext)
                }
            })

            currentProcess?.addDebugProcessListener(panelBuilderListener())
        }

        project.messageBus.connect()
            .subscribe<DebuggerManagerListener>(DebuggerManagerListener.TOPIC, object : DebuggerManagerListener {
                override fun sessionAttached(session: DebuggerSession?) {
                    emptyText.text = CoroutineStacksBundle.message("should.be.stopped.on.a.breakpoint")
                }

                override fun sessionCreated(session: DebuggerSession) {
                    session.process.addDebugProcessListener(panelBuilderListener())
                }

                override fun sessionRemoved(session: DebuggerSession) {
                    emptyText.text = CoroutineStacksBundle.message("no.java.debug.process.is.running")
                    emptyText.component.isVisible = true
                    panelContent.removeAll()
                }
            })
    }

    private fun panelBuilderListener() = object : DebugProcessListener {
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
            val selectedDispatcher = dispatcherDropdownMenu.selectedItem as? String
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
        suspendContextImpl.debugProcess.managerThread.invoke(object : DebuggerCommandImpl() {
            override fun action() {
                val root = Node()
                val coroutineStackForest = suspendContextImpl.buildCoroutineStackForest(
                    root,
                    coroutineDataList
                ) ?: run {
                    emptyText.text = CoroutineStacksBundle.message("nothing.to.show")
                    return
                }

                runInEdt {
                    forest.removeAll()
                    forest.add(coroutineStackForest)
                    updateUI()
                }
            }
        })
    }
}
