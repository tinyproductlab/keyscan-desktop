package com.keyscan.desktop

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Single runtime icon source shared by the main window and system tray. */
object DesktopAppIcon {
    val image: BufferedImage? by lazy {
        DesktopAppIcon::class.java.getResourceAsStream("/icons/keyscan-app.png")?.use(ImageIO::read)
    }
}
