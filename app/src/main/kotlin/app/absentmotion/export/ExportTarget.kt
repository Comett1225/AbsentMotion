package app.absentmotion.export

import app.absentmotion.platform.Uri
import java.io.OutputStream

interface ExportStore {
    fun save(folder: String?, name: String, mime: String, write: (OutputStream) -> Unit): Uri

    fun location(where: String): String = where

    fun delete(uri: Uri)
}

const val AppFolder = "AbsentMotion"

