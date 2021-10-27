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
import java.nio.charset.StandardCharsets
import java.util.*
import com.google.zxing.datamatrix.DataMatrixReader
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Rect
import kotlin.collections.ArrayList

class ProcessingClass {

    companion object {

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
         * Output: Boolean: true if no QR is detected, false otherwise; also modifies the ConcurrentLinkedDeque */
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

        /** Function to decode QR based on a OpenCV Mat
        * Input: OpenCV Mat(), Coroutine Scope, and ConcurrentLinkedDeque rxData
        * Output: No output. Prints the QR and kill other process trying to decode.
        * Note: runs in the Default Thread, not Main. Called from the decode() function */
        private fun decoderQR(gray: Mat, scopeDecode : CoroutineScope, rxData: ConcurrentLinkedDeque<String>) : Boolean  {
            //Zxing QR reader and hints for its configuration, unique for each thread.
            val hints = Hashtable<DecodeHintType, Any>()
            hints[DecodeHintType.CHARACTER_SET] = StandardCharsets.ISO_8859_1.name()//"utf-8"
            hints[DecodeHintType.TRY_HARDER] = true
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
            bitmap.recycle()

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
                        Log.i("RS","Bytes ISO: ${qrString.toByteArray(Charsets.ISO_8859_1).contentToString()}")
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

        /**Function to detect the active area of the FLC, as it reflects light it is brighter than the
         * 3D printed holder. Use of OpenCV contour detection and physical dimensions of the FLC. Main
         * logic: detect a rectangle that is not too big or too small and have an aspect ration smaller
         * than 16/9 or 4/3
         * Input: Bitmap, Array of Rectangle
         * Output: None, modifies the Array of Rectangle input */
        fun detectROI(mat : Mat, roiArray : ArrayList<Rect>) {
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
    }
}