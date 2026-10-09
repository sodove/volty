package ru.sodovaya.volty.data.nearby

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*

/** Whole encrypted snapshot; separate from logout-cleared credentials and Android backup. */
internal class AndroidPushInstallationStore(context: Context) : PushInstallationStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "nearby-push-installation.bin"))

    override suspend fun read(): PushInstallationSnapshot? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                val encrypted = readEncrypted() ?: return@synchronized null
                if (encrypted.size < IV_BYTES + TAG_BITS / 8) return@synchronized null
                val key = key(create = false) ?: return@synchronized null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, encrypted.copyOfRange(0, IV_BYTES)))
                cipher.updateAAD(AAD)
                PushInstallationCodec.decode(cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES))
            } catch (_: AEADBadTagException) {
                // Do not salvage an installation ID or generation from a corrupt/foreign snapshot.
                null
            } catch (_: Exception) {throw IOException("Push installation storage unavailable")}
        }
    }

    override suspend fun write(snapshot: PushInstallationSnapshot): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key(create = true)))
                check(cipher.iv.size == IV_BYTES)
                cipher.updateAAD(AAD)
                val encrypted = cipher.iv + cipher.doFinal(PushInstallationCodec.encode(snapshot))
                val output = file.startWrite()
                try {
                    output.write(encrypted); output.fd.sync(); file.finishWrite(output)
                    // AtomicFile can log some finish failures instead of throwing; verify actual commit.
                    check(readEncrypted()?.contentEquals(encrypted) == true)
                } catch (failure: Exception) {file.failWrite(output); throw failure}
            } catch (_: Exception) {throw IOException("Push installation storage unavailable")}
        }
    }

    private fun readEncrypted(): ByteArray? {
        val input = try {file.openRead()} catch (_: FileNotFoundException) {return null}
        return input.use {
            val bound = PushInstallationCodec.MAX_BYTES + IV_BYTES + TAG_BITS / 8
            val buffer = ByteArray(bound + 1)
            var used = 0
            while (used < buffer.size) {
                val count = it.read(buffer, used, buffer.size - used)
                if (count < 0) break
                if (count == 0) throw IOException("Push installation storage unavailable")
                used += count
            }
            if (used > bound) null else buffer.copyOf(used)
        }
    }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let {return it}
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
            generateKey()
        }
    }
    private companion object {
        val lock = Any()
        const val KEY_ALIAS = "volty.push.installation"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val AAD = "volty.push.installation.v1".encodeToByteArray()
    }
}
