package com.nikitanazarov.coroutinestacks.ui

import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.nikitanazarov.coroutinestacks.Constants
import com.nikitanazarov.coroutinestacks.CoroutineTrace
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsClassFinder
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.border.Border
import javax.swing.border.LineBorder

class CoroutineFramesList(
    suspendContext: SuspendContextImpl,
    trace: CoroutineTrace,
    lastRunningStackFrame: String
) : JBList<String>() {
    companion object {
        private val itemBorder = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.GRAY)
        private val leftPaddingBorder: Border = JBUI.Borders.emptyLeft(Constants.leftPaddingForBorder)
        private val compoundBorder = BorderFactory.createCompoundBorder(itemBorder, leftPaddingBorder)
    }

    init {
        val debugProcess = suspendContext.debugProcess

        val data = mutableListOf<String>()
        data.add(trace.header)
        data.addAll(trace.stackFrameItems.map { it.toString() })
        setListData(data.toTypedArray())
        val lastStackFrame = data.getOrNull(1)

        border = if (lastRunningStackFrame == lastStackFrame) {
            createRoundedBorder(JBColor.BLUE)
        } else {
            createRoundedBorder(JBColor.BLACK)
        }

        cellRenderer = CustomCellRenderer(trace.coroutinesActiveLabel)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                val list = e?.source as? JBList<*> ?: return
                val index = list.locationToIndex(e.point).takeIf { it > 0 } ?: return
                val stackFrameItem = trace.stackFrameItems[index - 1]

                stackFrameItem?.let { frameItem ->
                    val frame = frameItem.createFrame(debugProcess)

                    if (suspendContext.activeExecutionStack != null && frame != null) {
                        suspendContext.setCurrentStackFrame(suspendContext.activeExecutionStack, frame)
                    }
                }
            }
        })
    }

    // Copied from org.jetbrains.kotlin.idea.debugger.coroutine.view.CoroutineSelectedNodeListener#setCurrentStackFrame
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

    class CustomCellRenderer(
        private val coroutinesActive: String?,
    ) : DefaultListCellRenderer() {

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
}