package com.blankmediator.smsfromcsv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only provider for temporary outgoing MMS PDU files. */
class MmsPduProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.wap.mms-message"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("MMS PDU provider is read-only.")
        val file = resolve(uri)
        if (!file.isFile) throw FileNotFoundException("MMS PDU no longer exists.")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        columns.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolve(uri: Uri): File {
        val context = requireNotNull(context)
        val name = uri.lastPathSegment.orEmpty()
        if (!name.matches(Regex("[A-Za-z0-9._-]+\\.pdu"))) {
            throw FileNotFoundException("Invalid MMS PDU URI.")
        }
        val root = File(context.cacheDir, "mms_out").canonicalFile
        val file = File(root, name).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Invalid MMS PDU path.")
        }
        return file
    }
}
