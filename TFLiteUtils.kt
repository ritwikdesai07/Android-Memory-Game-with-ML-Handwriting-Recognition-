package com.example.hackerclicker

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

internal object TFLiteUtils {
    @RequiresApi(Build.VERSION_CODES.CUPCAKE)

    fun loadModelFile(context: Context, assetPath: String): MappedByteBuffer {
        context.assets.openFd(assetPath).use { fd ->
            fd.createInputStream().use { input ->
                val channel = input.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }
}

