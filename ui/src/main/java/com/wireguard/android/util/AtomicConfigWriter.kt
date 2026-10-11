package com.wireguard.android.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** No old config is touched until encryption and the complete, synced write succeed. */
object AtomicConfigWriter {
    fun write(file: File, encode: () -> ByteArray) {
        val bytes = encode()
        val temporary = File.createTempFile(".${file.name}.", ".pending", file.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("Could not atomically replace configuration")
        } finally {
            temporary.delete()
        }
    }
}
