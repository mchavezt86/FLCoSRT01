package com.android.flcosrt01.basic

import android.graphics.Camera
import android.util.Log
import android.util.Size
import com.android.flcosrt01.basic.CameraActivity.Companion.RS_TOTAL_SIZE
import com.android.flcosrt01.basic.CameraActivity.Companion.rs
import org.bytedeco.opencv.opencv_core.Mat
import java.util.concurrent.ConcurrentLinkedDeque
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.javacv.AndroidFrameConverter
import org.bytedeco.javacv.OpenCVFrameConverter
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.*
import java.nio.charset.StandardCharsets
import java.util.*
import com.backblaze.erasure.ReedSolomon
import com.google.zxing.datamatrix.DataMatrixReader
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Rect
import java.lang.StringBuilder
import kotlin.collections.ArrayList
import kotlin.properties.Delegates

class ProcessingClass {

    companion object {
        // String of a decoded QR
        //private lateinit var qrString : String

        // Number of ROI (Constant for this Fragment)
        //private val NUMBER_OF_ROIs = CameraActivity.numberOfTx

        //Sizes for Reed Solomon Encoder (Constant for this Fragment)
        //private val RS_DATA_SIZE = CameraActivity.rsDataSize //191
        //private const val RS_TOTAL_SIZE = 255
        //private val RS_PARITY_SIZE = RS_TOTAL_SIZE - RS_DATA_SIZE //64
        //Number of bytes in a QR code, version 1: 17 bytes
        //private val QR_BYTES = CameraActivity.qrBytes //17

        private var setQR_noDM = true

        fun setQR() {
            setQR_noDM = true
        }
        fun setDM() {
            setQR_noDM = false
        }

        /** Function to start the concurrent image processing of Mat objects and concurrent
         * attempts to decode QRs based on multi-threading.
         * Input: OpenCV Mat, ConcurrentLinkedDeque
         * Output: None, modifies the ConcurrentLinkedDeque */
        fun decodeQR(mat : Mat?, rxData : ConcurrentLinkedDeque<String>) : Boolean {
            val matEqP = Mat() // Equalised grayscale image
            val matNorm = Mat() // Normalised grayscale image
            var noQR = false
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
                    decoderQR(matNorm,this,rxData)
                }

                try {
                    noQR = runBlocking {
                        /*var noQR =*/ tmp1.await() && tmp2.await() && tmp3.await()
                    }
                }
                catch (e: CancellationException) {
                    Log.i("decodeScope", "Exception: $e")
                }
            }
            return noQR
        }

        /** Function to start the concurrent image processing of Mat objects and concurrent
         * attempts to decode QRs based on multi-threading.
         * Input: OpenCV Mat, ConcurrentLinkedDeque
         * Output: None, modifies the ConcurrentLinkedDeque */
        fun decodeQR(mat : Mat?, nextMat : Mat?, rxData : ConcurrentLinkedDeque<String>) : Boolean {
            val matEqP = Mat() // Equalised grayscale image
            val matNorm = Mat() // Normalised grayscale image
            val matMean = Mat () // Mean grayscale image
            var noQR = false
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
                    decoderQR(matNorm,this, rxData)
                }
                // Mean decoding
                val tmp4 = scopeDecode.async(scopeDecode.coroutineContext+Job()) {
                    //Log.d("AddMat","${it.size().height()}, ${it.size().width()}. ${nextMat!!.size().height()}, ${nextMat.size().width()}")
                    add(multiplyPut(it,0.5), multiplyPut(nextMat,0.5), matMean)
                    decoderQR(matMean,this, rxData)
                }

                try {
                    noQR = runBlocking {
                        /*var noQR =*/ tmp1.await() && tmp2.await() && tmp3.await() && tmp4.await()
                    }
                }
                catch (e: CancellationException) {
                    Log.i("decodeScope", "Exception: $e")
                }
            }
            return noQR
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
            //hints[DecodeHintType.POSSIBLE_FORMATS] = BarcodeFormat.QR_CODE
            hints[DecodeHintType.POSSIBLE_FORMATS] = if (setQR_noDM) { BarcodeFormat.QR_CODE } else { BarcodeFormat.DATA_MATRIX }

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
            //val qrReader = QRCodeReader()

            val qrReader = if (setQR_noDM) { QRCodeReader() } else { DataMatrixReader() }


            try { //Detect QR and print result
                result = qrReader.decode(binBitmap,hints)
                //Get the data in String and try to add it to the ConcurrentLinkedQueue: rxData
                val qrString = result.text
                /*Get the data in ByteArray and try to add it to the ConcurrentLinkedQueue: rxBytes.*/
                //Fully sync check and add data to the rxData.
                synchronized(rxData){
                    if (!rxData.contains(qrString) && scopeDecode.isActive){
                        rxData.add(qrString)
                        //Log.i("RS","String: $qrString")
                        Log.i("RS","Bytes ISO: ${qrString.toByteArray(Charsets.ISO_8859_1).contentToString()}")
                        //Log.i("RS","Bytes UTF: ${qrString.toByteArray(Charsets.UTF_8).contentToString()}")
                        //Log.i("RS","Byte number: ${qrString.toByteArray(charset = Charsets.ISO_8859_1)[0].toUByte()}")
                        scopeDecode.cancel("QR detected")
                        return false
                    }
                }
                return true //false
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
            /* Initialise variables */ Log.d("RS","Start of Reed-Solomon")
            val rsDataSize = CameraActivity.rsDataSize
            //val rsParitySize = RS_TOTAL_SIZE - rsDataSize
            val qrByte = CameraActivity.qrBytes
            //val rs = ReedSolomon.create(rsDataSize,rsParitySize)//RS_DATA_SIZE, RS_PARITY_SIZE)
            val byteMsg : Array<ByteArray> by lazy { Array(RS_TOTAL_SIZE) {ByteArray(qrByte-1) {0} } }
            val erasure : BooleanArray by lazy { BooleanArray(RS_TOTAL_SIZE){false} }
            val resultString = StringBuilder()

            /* First the data inside de rxData is ordered and stored in byteMsg while filling the
            * erasure array */
            // Array of bytes to store the data within the QR.
            val dataBytes = ByteArray(qrByte)
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
                rs.decodeMissing(byteMsg, erasure, 0, qrByte - 1)
                for (i in 0 until rsDataSize){
                    resultString.append(byteMsg[i].toString(Charsets.ISO_8859_1))
                }
            } catch (e: Exception){
                e.message?.let { Log.e("RS", it) }
                resultString.append("Error during Reed Solomon decoding.")
            }
            return resultString.toString()
        }

        /**Function to detect the active area of the FLC, as it reflects light it is brighter than the
         * 3D printed holder. Use of OpenCV contour detection and physical dimensions of the FLC. Main
         * logic: detect a rectangle that is not too big or too small and have an aspect ration smaller
         * than 16/9 or 4/3
         * Input: Bitmap, Array of Rectangle
         * Output: - */
        fun detectROI(mat : Mat, roiArray : ArrayList<Rect>) {
            //cvtColor(mat,mat, COLOR_BGR2GRAY) //Convert to GrayScale
            val binSize = mat.size().height()/2-1 //bin size for the binarisation
            //Constants for the area size
            val minArea = (mat.size().area()/50) //> 2% of total screen
            val maxArea = (mat.size().area()/5) //< 8% of total screen
            //Other variables
            val bin = Mat() //Mat for binarisation result
            val contours = MatVector() /*Contour detection returns a Mat Vector*/
            roiArray.clear() // This function needs to find 'X' ROIs at the same time.

            /*Image processing of the frame consists of:
            *- Adaptive Binarisation: large bin size allows to detect bigger features i.e. the FLC area
            *  so the bin size is half of the camera width.
            *  *********SUBJECT TO EVALUATION!!!!!!*********
            *- Contour extraction: we use only the contour variable which holds the detected contours in
            *  the form of a Mat vector*/
            adaptiveThreshold(mat,bin,255.0,ADAPTIVE_THRESH_GAUSSIAN_C,THRESH_BINARY,binSize,0.0)
            findContours(bin,contours,Mat(),RETR_LIST, CHAIN_APPROX_SIMPLE) //Mat() is for hierarchy [RETR_EXTERNAL]

            //Variable initialisation for the detected contour initialisation.
            var cnt : Mat
            var points : Mat
            var rect : Rect
            var aspect: Double
            //Final variable to return
            //var finalRect : Rect? = null

            /*Main for loop allows iteration. Contours.get(index) returns a Mat which holds the contour
            * points.*/
            loop@ for (i in 0 until contours.size()){
                cnt = contours.get(i)
                points = Mat() //Polygon points
                /*This function approximates each contour to a polygon. Parameters: source, destination,
                        * epsilon, closed polygon?. Epsilon: approximation accuracy. */
                approxPolyDP(cnt,points, 0.01*arcLength(cnt,true),true)

                /*The polygon approximation returns a Mat (name 'points'). The structure of this Mat is
                        * width = 1, height = number of vertices, channels = 2 (possibly coordinates).
                        * The steps from here are:
                        *- Select polygons with 4 corners
                        *- Select only polygons which are large enough in size.
                        *- Calculate a bounding rectangle for the polygon, for the FLC should be the actual FLC
                        *  area, thus the aspect ratio of this rectangle should be known.
                        *- Lastly, filter the rectangle based on its area not too big in size and have an aspect
                        *  ration between 0.5 and 2. We cannot tell if the rectangle is rotated but the aspect
                        *  ratio of the FLC is 16/9 = 1.7 or 9/16 = 0.56. However, if the FLC and the phone are
                        *  not parallel, the FLC might be a square. */
                if (points.size().height()==4){
                    if(contourArea(cnt) > minArea){ //Only large polygons.
                        rect = boundingRect(cnt)
                        aspect = (rect.width().toDouble()/rect.height().toDouble())
                        Log.i("Contour","Area=${contourArea(cnt)}")
                        Log.i("Contour","Aspect=${aspect}")
                        if (rect.area() < maxArea && aspect > 0.5 && aspect < 2.0){
                            //Save values of ROI for the decode function.
                            roiArray.add(rect)
                            Log.i("FLC","w=${rect.width()},h=${rect.height()}," +
                                    "x=${rect.x()},y=${rect.y()}")
                            if (roiArray.size == CameraActivity.numberOfTx) break@loop // break 'contours' for loop.
                        }
                    }
                }
            }
        }

        /**Function to detect the active area of the FLC, as it reflects light it is brighter than the
         * 3D printed holder. Use of OpenCV contour detection and physical dimensions of the FLC. Main
         * logic: detect a rectangle that is not too big or too small and have an aspect ration smaller
         * than 16/9 or 4/3
         * Input: Bitmap
         * Output: Rect (if the area is detected) or null if nothing is detected.*/
        fun detectROI(mat : Mat) : Rect? {
            //cvtColor(mat,mat, COLOR_BGR2GRAY) //Convert to GrayScale
            val binSize = mat.size().height()/2-1 //bin size for the binarisation
            //Constants for the area size
            val minArea = (mat.size().area()/50) //> 2% of total screen
            val maxArea = (mat.size().area()/5) //< 8% of total screen
            //Other variables
            val bin = Mat() //Mat for binarisation result
            val contours = MatVector() /*Contour detection returns a Mat Vector*/

            /*Image processing of the frame consists of:
            *- Adaptive Binarisation: large bin size allows to detect bigger features i.e. the FLC area
            *  so the bin size is half of the camera width.
            *  *********SUBJECT TO EVALUATION!!!!!!*********
            *- Contour extraction: we use only the contour variable which holds the detected contours in
            *  the form of a Mat vector*/
            adaptiveThreshold(mat,bin,255.0,ADAPTIVE_THRESH_GAUSSIAN_C,THRESH_BINARY,binSize,0.0)
            findContours(bin,contours,Mat(),RETR_LIST, CHAIN_APPROX_SIMPLE) //Mat() is for hierarchy [RETR_EXTERNAL]

            //Variable initialisation for the detected contour initialisation.
            var cnt : Mat
            var points : Mat
            var rect : Rect
            var aspect: Double
            //Final variable to return
            var finalRect : Rect? = null

            /*Main for loop allows iteration. Contours.get(index) returns a Mat which holds the contour
            * points.*/
            loop@ for (i in 0 until contours.size()){
                cnt = contours.get(i)
                points = Mat() //Polygon points
                /*This function approximates each contour to a polygon. Parameters: source, destination,
                        * epsilon, closed polygon?. Epsilon: approximation accuracy. */
                approxPolyDP(cnt,points, 0.01*arcLength(cnt,true),true)

                /*The polygon approximation returns a Mat (name 'points'). The structure of this Mat is
                        * width = 1, height = number of vertices, channels = 2 (possibly coordinates).
                        * The steps from here are:
                        *- Select polygons with 4 corners
                        *- Select only polygons which are large enough in size.
                        *- Calculate a bounding rectangle for the polygon, for the FLC should be the actual FLC
                        *  area, thus the aspect ratio of this rectangle should be known.
                        *- Lastly, filter the rectangle based on its area not too big in size and have an aspect
                        *  ration between 0.5 and 2. We cannot tell if the rectangle is rotated but the aspect
                        *  ratio of the FLC is 16/9 = 1.7 or 9/16 = 0.56. However, if the FLC and the phone are
                        *  not parallel, the FLC might be a square. */
                if (points.size().height()==4){
                    if(contourArea(cnt) > minArea){ //Only large polygons.
                        rect = boundingRect(cnt)
                        aspect = (rect.width().toDouble()/rect.height().toDouble())
                        Log.i("Contour","Area=${contourArea(cnt)}")
                        Log.i("Contour","Aspect=${aspect}")
                        if (rect.area() < maxArea && aspect > 0.5 && aspect < 2.0){
                            //Save values of ROI for the decode function.
                            finalRect = rect
                            Log.i("FLC","w=${finalRect.width()},h=${finalRect.height()}," +
                                    "x=${finalRect.x()},y=${finalRect.y()}")
                            break@loop // break 'contours' for loop.
                        }
                    }
                }
            }
            return finalRect
        }

        /**Function to:
         * Compute the rectangle of the display (ROI) into camera array coordinates (i.e. the zoom)
         * to set the auto-exposure (AE) and auto-focus (AF) regions.
         * Inputs:
         * - Zoom Rect: camera array to zoom in 4x.
         * - BmpSurface Bitmap: bitmap of the zoomed surface
         * - ROI Rect: rectangle of the ROI in BmpSurface coordinates
         * To set the AE and AF regions, the coordinates must be in the camera array coordinates
         * but the detected ROI of the FLC display is relative to the Bitmap generated for detection
         * */
        fun scaleRect(zoom : android.graphics.Rect, prevSize: Size, rectROI : Rect) : android.graphics.Rect {
            val left = zoom.left + rectROI.y() * zoom.height() / prevSize.height
            val top = zoom.top + rectROI.x() * zoom.height() / prevSize.height
            val right = left + rectROI.width() * zoom.height() / prevSize.height
            val bottom = top + rectROI.height() * zoom.height() / prevSize.height
            //Log.i("FLC","scale: $scale, left: $left, top: $top, right: $right, bottom: $bottom")

            return android.graphics.Rect(left,top,right,bottom)
        }
    }
}