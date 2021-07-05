package com.android.flcosrt01.basic

import android.util.Log
import org.bytedeco.opencv.opencv_core.Mat
import java.util.concurrent.ConcurrentLinkedDeque
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.javacv.AndroidFrameConverter
import org.bytedeco.javacv.OpenCVFrameConverter
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.*
import org.bytedeco.opencv.global.opencv_core.normalize
import java.nio.charset.StandardCharsets
import java.util.*

class ProcessingClass {
    companion object {
        private lateinit var qrString : String

        suspend fun decodeQR(mat : Mat?, rxData : ConcurrentLinkedDeque<String>){
            val matEqP = Mat() // Equalised grayscale image
            val matNorm = Mat() // Normalised grayscale image
            mat?.let {
                val jobDecode = Job()
                val scopeDecode = CoroutineScope(Dispatchers.Default+jobDecode)

                val tmp1 = scopeDecode.async(scopeDecode.coroutineContext+Job()) {
                    decoderQR(it,this, rxData)
                }

                val tmp2 = scopeDecode.async(scopeDecode.coroutineContext+Job()) {
                    equalizeHist(it, matEqP)
                    decoderQR(matEqP,this, rxData)
                }

                val tmp3 = scopeDecode.async(scopeDecode.coroutineContext+Job()) {
                    normalize(it,matNorm)
                    decoderQR(it,this,rxData)
                }

                try {
                    var noQR = tmp1.await() && tmp3.await() && tmp3.await()
                }
                catch (e: CancellationException) {
                    Log.i("decodeScope", "Exception: $e")
                }
            }
        }
        /** Function to decode QR based on a OpenCV Mat
        * Input: OpenCV Mat(), Coroutine Scope, and ConcurrentLinkedDeque rxData
        * Output: No output. Prints the QR and kill other process trying to decode.
        * Note: runs in the Default Thread, not Main. Called from the decode() function */
        private fun decoderQR(gray: Mat, scopeDecode : CoroutineScope, rxData: ConcurrentLinkedDeque<String>) : Boolean  {
            //Zxing QR reader and hints for its configuration, unique for each thread.
            val hints = Hashtable<DecodeHintType, Any>()
            hints[DecodeHintType.CHARACTER_SET] = StandardCharsets.ISO_8859_1.name()//"utf-8"
            hints[DecodeHintType.TRY_HARDER] = true
            hints[DecodeHintType.POSSIBLE_FORMATS] = BarcodeFormat.QR_CODE

            val rgba = Mat()
            cvtColor(gray,rgba, COLOR_GRAY2RGBA)
            val converterMat = OpenCVFrameConverter.ToMat()
            val frame = converterMat.convert(rgba)
            val converterAnd = AndroidFrameConverter()
            val bitmap = converterAnd.convert(frame)
            val intData = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(intData,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val lumSource : LuminanceSource = RGBLuminanceSource(bitmap.width, bitmap.height,intData)
            val binBitmap = BinaryBitmap(HybridBinarizer(lumSource))
            //Store result of QR detection
            val result : Result
            val qrReader = QRCodeReader()

            try { //Detect QR and print result
                result = qrReader.decode(binBitmap,hints)
                //Get the data in String and try to add it to the ConcurrentLinkedQueue: rxData
                qrString = result.text
                /*Get the data in ByteArray and try to add it to the ConcurrentLinkedQueue: rxBytes.*/
                //Fully sync check and add data to the rxData.
                synchronized(rxData){
                    if (!rxData.contains(qrString)){
                        rxData.add(qrString)
                        //Log.i("RS","String: $qrString")
                        Log.i("RS","Bytes ISO: ${qrString.toByteArray(Charsets.ISO_8859_1).contentToString()}")
                        //Log.i("RS","Bytes UTF: ${qrString.toByteArray(Charsets.UTF_8).contentToString()}")
                        //Log.i("RS","Byte number: ${qrString.toByteArray(charset = Charsets.ISO_8859_1)[0].toUByte()}")
                        scopeDecode.cancel("QR detected")
                    }
                }
                return false
            }
            catch (e : NotFoundException){//NotFoundException){
                //Log.i("QR Reader","Not found")
                return true
            }
            catch (e: ChecksumException){
                //Log.i("QR Reader","Checksum failed")
                return true
            }
            catch (e: FormatException){
                //Log.i("QR Reader","Format error")
                return true
            }
        }
    }

}