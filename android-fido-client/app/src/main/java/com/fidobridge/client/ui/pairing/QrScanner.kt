package com.fidobridge.client.ui.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView

@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val barcodeView = remember {
        BarcodeView(context).apply {
            decodeSingle(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    result.text?.let { onResult(it) }
                }

                override fun possibleResultPoints(result: List<ResultPoint>) = Unit
            })
        }
    }

    DisposableEffect(Unit) {
        barcodeView.resume()
        onDispose { barcodeView.pause() }
    }

    AndroidView(
        modifier = modifier,
        factory = { barcodeView }
    )
}
