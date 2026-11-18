package io.github.hellesponty.aspire

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.LayeredIcon
import com.intellij.util.IconUtil
import javax.swing.Icon
import javax.swing.SwingConstants

object AspireIcons {
    @JvmField
    val Aspire: Icon = IconLoader.getIcon("/icons/aspire.svg", AspireIcons::class.java)

    /** Base icon with a small status badge in the bottom-right corner. */
    fun withStatus(base: Icon, badge: Icon): Icon =
        LayeredIcon(2).apply {
            setIcon(base, 0)
            setIcon(IconUtil.scale(badge, null, 0.6f), 1, SwingConstants.SOUTH_EAST)
        }
}
