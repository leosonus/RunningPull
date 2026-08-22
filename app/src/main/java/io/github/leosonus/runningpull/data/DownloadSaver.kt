package io.github.leosonus.runningpull.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** 최종 JSON을 기기의 공용 Downloads/strongRunner 폴더에 저장한다. */
object DownloadSaver {

    private const val SUBFOLDER = "strongRunner"

    fun saveJson(context: Context, fileName: String, content: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, fileName, content)
        } else {
            saveLegacy(fileName, content)
        }
    }

    private fun saveViaMediaStore(context: Context, fileName: String, content: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads/$SUBFOLDER 에 파일을 생성하지 못했습니다")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Downloads/$SUBFOLDER 파일을 열지 못했습니다")
        return uri
    }

    private fun saveLegacy(fileName: String, content: String): Uri {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val subDir = File(downloadsDir, SUBFOLDER)
        if (!subDir.exists() && !subDir.mkdirs()) {
            throw IOException("Downloads/$SUBFOLDER 폴더를 만들지 못했습니다 (저장소 권한 확인 필요)")
        }
        val file = File(subDir, fileName)
        try {
            file.writeText(content, Charsets.UTF_8)
        } catch (e: SecurityException) {
            throw IOException("저장소 권한이 없어 $fileName 을 저장하지 못했습니다")
        }
        return Uri.fromFile(file)
    }
}
