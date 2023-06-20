package com.nikitanazarov.coroutinestacks.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.util.*
import javax.swing.ScrollPaneLayout

class ForestLayout(private val xPadding: Int = 50, private val yPadding: Int = 50) : ScrollPaneLayout() {
    override fun addLayoutComponent(name: String?, comp: Component?) {
    }

    override fun removeLayoutComponent(comp: Component?) {
    }

    override fun preferredLayoutSize(parent: Container): Dimension {
        val size = parent.componentCount
        val widthToDrawSubtree = Array(size) { -xPadding }
        var maxY = 0
        var width = 0
        var currentHeight = yPadding
        parent.dfs(object : ComponentVisitor {
            override fun visitComponent(parentIndex: Int, index: Int) {
                currentHeight += parent.getComponentSize(index).height + yPadding
                if (maxY < currentHeight) {
                    maxY = currentHeight
                }
            }

            override fun leaveComponent(parentIndex: Int, index: Int) {
                val compSize = parent.getComponentSize(index)
                currentHeight -= yPadding + compSize.height
                if (parentIndex < 0) {
                    width += widthToDrawSubtree[index] + xPadding
                    return
                }

                if (widthToDrawSubtree[index] <= 0) {
                    widthToDrawSubtree[index] = compSize.width + xPadding
                }

                widthToDrawSubtree[parentIndex] += widthToDrawSubtree[index] + xPadding
            }
        })

        // When visiting roots we have added one extra padding, so we need to remove it here
        if (width > 0) {
            width -= xPadding
        }

        val insets = parent.insets
        return Dimension(width + insets.left + insets.right, maxY + insets.top + insets.bottom)
    }

    override fun minimumLayoutSize(parent: Container): Dimension = parent.preferredSize

    override fun layoutContainer(parent: Container) {
        val size = parent.componentCount
        if (size == 0) {
            return
        }

        val widthToDrawSubtree = Array(size) { -xPadding }
        val ys = Array(size) { 0 }
        val xs = Array(size) { 0 }
        val parentSize = parent.size
        var currentHeight = parentSize.height - yPadding
        parent.dfs(object : ComponentVisitor {
            override fun visitComponent(parentIndex: Int, index: Int) {
                currentHeight -= parent.getComponentSize(index).height
                ys[index] = currentHeight
                currentHeight -= yPadding
            }

            override fun leaveComponent(parentIndex: Int, index: Int) {
                val compSize = parent.getComponentSize(index)
                currentHeight += yPadding + compSize.height
                if (parentIndex < 0) {
                    return
                }

                if (widthToDrawSubtree[index] <= 0) {
                    widthToDrawSubtree[index] = compSize.width + xPadding
                }
                widthToDrawSubtree[parentIndex] += widthToDrawSubtree[index] + xPadding
            }
        })

        var currentX = 0
        parent.dfs(object : ComponentVisitor {
            override fun visitComponent(parentIndex: Int, index: Int) {
                xs[index] = currentX + widthToDrawSubtree[index] / 2 - parent.getComponentSize(index).width / 2
            }

            override fun leaveComponent(parentIndex: Int, index: Int) {
                currentX = xs[index] + widthToDrawSubtree[index] / 2 + xPadding + parent.getComponentSize(index).width / 2
            }
        })

        for (i in 0 until size)  {
            val comp = parent.getComponent(i)
            if (!comp.isVisible || comp is Separator) {
                continue
            }

            val compSize = comp.preferredSize
            comp.setBounds(xs[i], ys[i], compSize.width, compSize.height)
        }
    }

    private fun Container.getComponentSize(index: Int): Dimension =
        getComponent(index).preferredSize
}

class Separator : Component()

internal interface ComponentVisitor {
    fun visitComponent(parentIndex: Int, index: Int) {
    }

    fun leaveComponent(parentIndex: Int, index: Int) {
    }
}

internal fun Container.dfs(visitor: ComponentVisitor) {
    val stack = Stack<Int>()
    val parents = Stack<Int>()
    stack.add(0)
    parents.add(-1)
    while (stack.isNotEmpty()) {
        var currentIndex = stack.pop()
        var currentParent = parents.peek()
        visitor.visitComponent(currentParent, currentIndex)
        var i = currentIndex + 1
        while (i < componentCount) {
            if (getComponent(i) is Separator) {
                visitor.leaveComponent(currentParent, currentIndex)
                currentIndex = currentParent
                if (currentParent != -1) {
                    parents.pop()
                    currentParent = parents.peek()
                }
                i += 1
            } else {
                stack.push(i)
                parents.push(currentIndex)
                break
            }
        }
    }
}