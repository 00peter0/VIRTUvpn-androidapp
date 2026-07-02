/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.configStore

import android.content.Context
import android.util.Log
import com.wireguard.android.R
import com.wireguard.android.util.AndroidKeystoreAead
import com.wireguard.android.util.EncryptedSecretCodec
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Configuration store that uses a `wg-quick`-style file for each configured tunnel.
 */
class FileConfigStore(private val context: Context) : ConfigStore {
    @Throws(IOException::class)
    override fun create(name: String, config: Config): Config {
        Log.d(TAG, "Creating configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.createNewFile())
            throw IOException(context.getString(R.string.config_file_exists_error, file.name))
        writeEncryptedConfig(file, config)
        return config
    }

    @Throws(IOException::class)
    override fun delete(name: String) {
        Log.d(TAG, "Deleting configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.delete())
            throw IOException(context.getString(R.string.config_delete_error, file.name))
    }

    override fun enumerate(): Set<String> {
        return context.fileList()
            .filter { it.endsWith(".conf") }
            .map { it.substring(0, it.length - ".conf".length) }
            .toSet()
    }

    private fun fileFor(name: String): File {
        return File(context.filesDir, "$name.conf")
    }

    @Throws(BadConfigException::class, IOException::class)
    override fun load(name: String): Config {
        val file = fileFor(name)
        val stored = FileInputStream(file).use { stream -> stream.readBytes().toString(StandardCharsets.UTF_8) }
        val configText = if (EncryptedSecretCodec.isEncryptedValue(stored)) {
            codec.decrypt(stored)
        } else {
            stored
        }
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))
        if (!EncryptedSecretCodec.isEncryptedValue(stored)) {
            Log.i(TAG, "Migrating plaintext WireGuard configuration for tunnel $name to encrypted storage")
            writeEncryptedText(file, configText)
        }
        return config
    }

    @Throws(IOException::class)
    override fun rename(name: String, replacement: String) {
        Log.d(TAG, "Renaming configuration for tunnel $name to $replacement")
        val file = fileFor(name)
        val replacementFile = fileFor(replacement)
        if (!replacementFile.createNewFile()) throw IOException(context.getString(R.string.config_exists_error, replacement))
        if (!file.renameTo(replacementFile)) {
            if (!replacementFile.delete()) Log.w(TAG, "Couldn't delete marker file for new name $replacement")
            throw IOException(context.getString(R.string.config_rename_error, file.name))
        }
    }

    @Throws(IOException::class)
    override fun save(name: String, config: Config): Config {
        Log.d(TAG, "Saving configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.isFile)
            throw FileNotFoundException(context.getString(R.string.config_not_found_error, file.name))
        writeEncryptedConfig(file, config)
        return config
    }

    private fun writeEncryptedConfig(file: File, config: Config) {
        writeEncryptedText(file, config.toWgQuickString())
    }

    private fun writeEncryptedText(file: File, configText: String) {
        FileOutputStream(file, false).use { stream ->
            stream.write(codec.encrypt(configText).toByteArray(StandardCharsets.UTF_8))
        }
    }

    companion object {
        private const val TAG = "WireGuard/FileConfigStore"
        private const val CONFIG_KEY_ALIAS = "virtuvpn_wireguard_config_aes_gcm"
        private val codec = EncryptedSecretCodec(AndroidKeystoreAead(CONFIG_KEY_ALIAS))
    }
}
