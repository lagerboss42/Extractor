package com.majk.extractor.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.parser.OpterListTextParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OCR for images shared into Extractor (Opter stop-list screenshots). */
object ImageOcrImporter {

    private const val TAG = "ImageOcrImporter"

    suspend fun importUri(context: Context, uri: Uri): List<Shipment> {
        val bitmap = loadBitmap(context, uri) ?: return emptyList()
        val text = recognize(bitmap)
        Log.i(TAG, "OCR chars=${text.length}")
        return OpterListTextParser.parse(text)
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = false
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmap: ${e.message}")
            null
        }
    }

    private suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text ?: "")
                    recognizer.close()
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                    recognizer.close()
                }
        }
}
