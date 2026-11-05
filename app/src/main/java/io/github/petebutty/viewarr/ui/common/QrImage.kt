package io.github.petebutty.viewarr.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        val size = matrix.width
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        filterQuality = FilterQuality.None,
        modifier = modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp),
    )
}
