package com.nikitanazarov.coroutinestacks.ui

import java.awt.*
import java.awt.event.*
import javax.swing.JPanel
import javax.swing.JViewport

class DraggablePanel : JPanel(), MouseListener, MouseMotionListener {
    private var holdPointOnView: Point? = null

    init {
        addMouseMotionListener(this)
        addMouseListener(this)
    }

    override fun mouseDragged(e: MouseEvent) {
        val viewport = parent as? JViewport ?: return
        val holdPointOnView = holdPointOnView ?: return
        val dragEventPoint = e.point
        val viewPos = viewport.viewPosition
        val maxViewPosX = width - viewport.width
        val maxViewPosY = height - viewport.height
        if (width > viewport.width) {
            viewPos.x -= dragEventPoint.x - holdPointOnView.x
            if (viewPos.x < 0) {
                viewPos.x = 0
                holdPointOnView.x = dragEventPoint.x
            }
            if (viewPos.x > maxViewPosX) {
                viewPos.x = maxViewPosX
                holdPointOnView.x = dragEventPoint.x
            }
        }

        if (height > viewport.height) {
            viewPos.y -= dragEventPoint.y - holdPointOnView.y
            if (viewPos.y < 0) {
                viewPos.y = 0
                holdPointOnView.y = dragEventPoint.y
            }
            if (viewPos.y > maxViewPosY) {
                viewPos.y = maxViewPosY
                holdPointOnView.y = dragEventPoint.y
            }
        }

        viewport.viewPosition = viewPos
    }

    override fun mousePressed(e: MouseEvent?) {
        e ?: return
        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        holdPointOnView = e.point
    }

    override fun mouseReleased(e: MouseEvent?) {
        setCursor(null)
    }

    override fun mouseMoved(e: MouseEvent?) {}
    override fun mouseClicked(e: MouseEvent?) {}
    override fun mouseEntered(e: MouseEvent?) {}
    override fun mouseExited(e: MouseEvent?) {}
}
