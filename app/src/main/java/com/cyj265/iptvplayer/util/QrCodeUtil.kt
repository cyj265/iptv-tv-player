package com.cyj265.iptvplayer.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** 二维码生成（ZXing 纯编码，无需相机权限）。 */
object QrCodeUtil {

    /** 把文本编码为二维码 Bitmap；失败返回 null。 */
    fun encode(text: String, sizePx: Int = 640): Bitmap? {
        return try {
            val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (bits.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
