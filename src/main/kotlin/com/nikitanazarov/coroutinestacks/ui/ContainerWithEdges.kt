package com.nikitanazarov.coroutinestacks.ui

import com.intellij.ui.JBColor
import java.awt.*
import java.awt.geom.Path2D

class ContainerWithEdges : Container() {
    companion object {
        val bezierCurveControlPointOffset = 20
        val edgeWidth = 1.0F
        val edgeColor = JBColor.BLUE
    }

    override fun paint(g: Graphics) {
        super.paint(g)
        val g2d = g as? Graphics2D ?: return

        dfs(object : ComponentVisitor {
            override fun visitComponent(parentIndex: Int, index: Int) {
                if (parentIndex < 0) return
                val comp = getComponent(index)
                val parentComp = getComponent(parentIndex)
                val parentTopCenter = Point(parentComp.x + parentComp.preferredSize.width / 2, parentComp.y)
                val compBottomCenter = Point(comp.x + comp.preferredSize.width / 2, comp.y + comp.preferredSize.height)
                updateGraphicsPreferences(g2d)
                g2d.draw(calculateBezierCurve(parentTopCenter, compBottomCenter))
            }
        })
    }

    private fun calculateBezierCurve(start: Point, end: Point): Path2D {
        val path = Path2D.Double()
        path.moveTo(start.x.toDouble(), start.y.toDouble())
        path.curveTo(
            start.x.toDouble(), start.y.toDouble() - bezierCurveControlPointOffset,
            end.x.toDouble(), end.y.toDouble() + bezierCurveControlPointOffset,
            end.x.toDouble(), end.y.toDouble()
        )
        return path
    }

    private fun updateGraphicsPreferences(g: Graphics2D) {
        g.stroke = BasicStroke(edgeWidth)
        g.color = edgeColor
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    }
}
