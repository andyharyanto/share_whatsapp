package id.my.alan.share_whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.content.FileProvider
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.IOException

/** ShareWhatsappPlugin */
class ShareWhatsappPlugin : FlutterPlugin, MethodCallHandler {
    private val TAG = "SHARE_WHATSAPP"

    private lateinit var channel: MethodChannel
    private var context: Context? = null

    private val providerAuthority: String
        get() = "${context?.packageName}.provider"

    private val shareCacheFolder: File?
        get() {
            val ctx = context ?: return null
            return File(ctx.cacheDir, "share_whatsapp")
        }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "share_whatsapp")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        context = null
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        Log.d(TAG, "method=${call.method}, argument=${call.arguments}")

        when (call.method) {
            "installed" -> installed(call, result)
            "share" -> share(call, result)
            else -> result.notImplemented()
        }
    }

    private fun installed(@NonNull call: MethodCall, @NonNull result: Result) {
        val ctx = context ?: run {
            result.error("INVALID_CONTEXT", "No application context found", null)
            return
        }
    
        val packageName = call.arguments as? String
        // Perbaikan typo: ganti isNull_or_empty() menjadi isNullOrEmpty()
        if (packageName.isNullOrEmpty()) {
            result.error("INVALID_ARGUMENT", "Package name cannot be null or empty", null)
            return
        }
    
        try {
            // Setelah guard clause di atas, Kotlin otomatis mengenali packageName sebagai String (non-null)
            val isInstalled = isPackageInstalled(packageName, ctx.packageManager)
            result.success(if (isInstalled) 1 else 0)
        } catch (e: Exception) {
            result.error("ERROR_INSTALLED", e.message, null)
        }
    }

    private fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun clearShareCacheFolder() {
        val folder = shareCacheFolder ?: return
        if (folder.exists()) {
            folder.listFiles()?.forEach { file ->
                // Hapus file cache yang umurnya lebih dari 1 jam agar tidak mengganggu proses share berjalan
                if (System.currentTimeMillis() - file.lastModified() > 3600_000) {
                    file.delete()
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyToShareCacheFolder(file: File): File {
        val folder = shareCacheFolder ?: throw IOException("Cache folder unavailable")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        // Gunakan timestamp agar nama file unik jika dikirim berulang
        val newFile = File(folder, "${System.currentTimeMillis()}_${file.name}")
        file.copyTo(newFile, true)
        return newFile
    }

    private fun share(@NonNull call: MethodCall, @NonNull result: Result) {
        val ctx = context ?: run {
            result.error("INVALID_CONTEXT", "No application context found", null)
            return
        }

        try {
            clearShareCacheFolder()

            val packageName = call.argument<String>("packageName") ?: "com.whatsapp"
            val rawPhone = call.argument<String?>("phone")
            val text = call.argument<String?>("text")
            val contentType = call.argument<String?>("contentType")
            val filePath = call.argument<String?>("file")

            // Bersihkan nomor telepon (hanya sisipkan angka saja)
            val phone = rawPhone?.replace(Regex("[^0-9]"), "")

            // 1. Kirim File (Gambar, Video, PDF, dll.)
            if (!filePath.isNullOrEmpty()) {
                val fileToShare = File(filePath)
                if (!fileToShare.exists()) {
                    result.error("FILE_NOT_FOUND", "File does not exist: $filePath", null)
                    return
                }

                val cachedFile = copyToShareCacheFolder(fileToShare)
                val fileUri: Uri = FileProvider.getUriForFile(ctx, providerAuthority, cachedFile)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    setPackage(packageName)
                    type = contentType ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, fileUri)

                    if (!text.isNullOrEmpty()) {
                        putExtra(Intent.EXTRA_TEXT, text)
                    }

                    if (!phone.isNullOrEmpty()) {
                        putExtra("jid", "$phone@s.whatsapp.net")
                    }

                    // PERBAIKAN UTAMA: Flag permission wajib ditaruh langsung di Intent utama
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // Berikan izin URI secara langsung ke package WhatsApp target
                ctx.grantUriPermission(packageName, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                ctx.startActivity(intent)
                result.success(1)
                return
            }

            // 2. Kirim Teks Langsung ke Nomor Tertentu (Deep Linking resmi WhatsApp)
            if (!phone.isNullOrEmpty()) {
                val encodedText = Uri.encode(text ?: "")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encodedText")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                result.success(1)
                return
            }

            // 3. Kirim Teks Biasa (Pilih Kontak Manual di WhatsApp)
            if (!text.isNullOrEmpty()) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    setPackage(packageName)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                result.success(1)
                return
            }

            result.error("INVALID_ARGUMENTS", "Either file, text, or phone must be provided", null)

        } catch (e: Exception) {
            Log.e(TAG, "Error while sharing to WhatsApp", e)
            result.error("ERROR_SHARE", e.message, null)
        }
    }
}
