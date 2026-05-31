// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.util

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

/**
 * AWT Transferable carrying a list of files for system drag-and-drop.
 *
 * Uses [DataFlavor.javaFileListFlavor] — the cross-platform standard
 * recognised by DaVinci Resolve, Premiere Pro, Final Cut Pro, and every
 * major file manager on macOS, Windows, and Linux.
 */
class FileTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> =
        arrayOf(DataFlavor.javaFileListFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor == DataFlavor.javaFileListFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return files
    }
}
