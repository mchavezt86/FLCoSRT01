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
import com.backblaze.erasure.ReedSolomon
import java.lang.StringBuilder

class ProcessingClass {
    companion object {
        // String of a decoded QR
        private lateinit var qrString : String

        //Sizes for Reed Solomon Encoder
        private const val RS_DATA_SIZE = 191
        private const val RS_PARITY_SIZE = 64
        private const val RS_TOTAL_SIZE = 255
        //Number of bytes in a QR code, version 1: 17 bytes
        private const val QR_BYTES = 17

        /** Function to start the concurrent image processing of Mat objects and concurrent
         * attempts to decode QRs based on multi-threading.
         * Input: OpenCV Mat, ConcurrentLinkedDeque
         * Output: None, modifies the ConcurrentLinkedDeque */
        fun decodeQR(mat : Mat?, rxData : ConcurrentLinkedDeque<String>){
            val matEqP = Mat() // Equalised grayscale image
            val matNorm = Mat() // Normalised grayscale image
            mat?.let {
                // Variables to control multi-thread environment
                val jobDecode = Job()
                val scopeDecode = CoroutineScope(Dispatchers.Default+jobDecode)

                // Grayscale decoding
                val tmp1 = scopeDecode.async(scopeDecode.coroutineContext+Job()) {
                    decoderQR(it,this, rxData)
                }
                // Equalisation decoding
                val tmp2 = scopeDecode.async(scopeDecode.coroutineContext+Job()) {
                    equalizeHist(it, matEqP)
                    decoderQR(matEqP,this, rxData)
                }
                // Normalisation decoding
                val tmp3 = scopeDecode.async(scopeDecode.coroutineContext+Job()) {
                    normalize(it,matNorm)
                    decoderQR(it,this,rxData)
                }

                try {
                    runBlocking {
                        var noQR = tmp1.await() && tmp2.await() && tmp3.await()
                    }
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

        /** Function to apply the Reed-Solomon Forward Error Correction (RS-FEC) using the erasure
         * logic.
         * Input: ConcurrentLinkedDeque
         * Output: String */
        fun reedSolomonFEC(rxData: ConcurrentLinkedDeque<String>) : String {
            /* Initialise variables */
            val rs = ReedSolomon.create(RS_DATA_SIZE, RS_PARITY_SIZE)
            val byteMsg : Array<ByteArray> by lazy { Array(RS_TOTAL_SIZE) {ByteArray(QR_BYTES-1) {0} } }
            val erasure : BooleanArray by lazy { BooleanArray(RS_TOTAL_SIZE){false} }
            val resultString = StringBuilder()

            /* First the data inside de rxData is ordered and stored in byteMsg while filling the
            * erasure array */
            // Array of bytes to store the data within the QR.
            val dataBytes = ByteArray(QR_BYTES)
            rxData.forEach {
                /*Convert the QR data, stored as a String and copy it into a Byte Array. The charset is
                * not mandatory but advisable */
                it.toByteArray(charset=Charsets.ISO_8859_1).copyInto(dataBytes)
                /*Copy and set data into the arrays needed to decode the using RS. These bytes may be
                * interpreted as signed integers. The first byte contains the index in which the 16
                * bytes of data should be stored and as the index must be Int (and does not accept an
                * UInt) we mask the first byte using a bitwise AND with 0xFF (first byte). The index
                * is also use to fill the erasure array, as they state which QRs are present. */
                dataBytes.copyInto(byteMsg[dataBytes[0].toInt() and 0xFF],0,1)
                erasure[it[0].toInt() and 0xFF]=true
                Log.i("RS","${dataBytes[0].toInt() and 0xFF}: " +
                        byteMsg[dataBytes[0].toInt() and 0xFF].contentToString()
                )
            }

            /*Apply Reed Solomon, inside a try-catch:
        * Try to perform the Reed Solomon decoding and modify the text using the StringBuilder. If
        * the decoding fails the StringBuilder shows an error message.*/
            try {
                rs.decodeMissing(byteMsg, erasure, 0, QR_BYTES - 1)
                for (i in 0 until RS_DATA_SIZE){
                    resultString.append(byteMsg[i].toString(Charsets.ISO_8859_1))
                }
            } catch (e: Exception){
                Log.e("RS",e.message)
                resultString.append("Error during Reed Solomon decoding.")
            }

            return resultString.toString()
        }
    }

}