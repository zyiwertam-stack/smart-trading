package com.smarttrading.vision

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max

class OpenCvProcessor(private val maximumDimension: Int = 1280) {
    init {
        require(maximumDimension >= 320)
    }

    fun process(bitmap: Bitmap, capturedAt: Instant): ProcessedFrame {
        check(OpenCVLoader.initLocal()) { "OpenCV native runtime could not be initialized" }
        val source = Mat()
        val resized = Mat()
        val rgb = Mat()
        val lab = Mat()
        val channels = ArrayList<Mat>()
        val equalizedL = Mat()
        val enhancedLab = Mat()
        val enhancedRgb = Mat()
        val denoisedRgb = Mat()
        val gray = Mat()
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        val hsv = Mat()
        val saturationMask = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        val clahe: CLAHE = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        try {
            Utils.bitmapToMat(bitmap, source)
            val scale = minOf(1.0, maximumDimension.toDouble() / max(source.cols(), source.rows()))
            Imgproc.resize(source, resized, Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_RGBA2RGB)

            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            Core.split(lab, channels)
            clahe.apply(channels[0], equalizedL)
            channels[0].release()
            channels[0] = equalizedL
            Core.merge(channels, enhancedLab)
            Imgproc.cvtColor(enhancedLab, enhancedRgb, Imgproc.COLOR_Lab2RGB)
            Imgproc.bilateralFilter(enhancedRgb, denoisedRgb, 5, 35.0, 35.0)

            Imgproc.cvtColor(denoisedRgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.extractChannel(hsv, saturationMask, 1)
            Imgproc.threshold(saturationMask, saturationMask, 45.0, 255.0, Imgproc.THRESH_BINARY)
            val morphologyKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
            Imgproc.morphologyEx(
                saturationMask,
                saturationMask,
                Imgproc.MORPH_OPEN,
                morphologyKernel
            )
            morphologyKernel.release()
            Imgproc.cvtColor(denoisedRgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.Canny(gray, edges, 55.0, 150.0)
            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE
            )
            val regions = contours.asSequence()
                .map { Imgproc.boundingRect(it) }
                .filter { it.width >= enhancedRgb.cols() / 12 && it.height >= enhancedRgb.rows() / 16 }
                .map { it.toPixelRect() }
                .distinct()
                .take(100)
                .toList()

            val width = denoisedRgb.cols()
            val height = denoisedRgb.rows()
            val bytes = ByteArray(width * height * 3)
            denoisedRgb.get(0, 0, bytes)
            val pixels = IntArray(width * height) { index ->
                val offset = index * 3
                val red = bytes[offset].toInt() and 0xff
                val green = bytes[offset + 1].toInt() and 0xff
                val blue = bytes[offset + 2].toInt() and 0xff
                (red shl 16) or (green shl 8) or blue
            }
            val frame = PixelFrame(width, height, pixels, capturedAt)
            val edgeDensity = Core.countNonZero(edges).toDouble() / (width * height)
            val chromaDensity = Core.countNonZero(saturationMask).toDouble() / (width * height)
            Core.meanStdDev(gray, mean, deviation)
            val contrast = deviation.toArray().firstOrNull()?.div(80.0)?.coerceIn(0.0, 1.0) ?: 0.0
            val quality = (
                0.20 +
                    (edgeDensity / 0.04).coerceIn(0.0, 1.0) * 0.30 +
                    (chromaDensity / 0.08).coerceIn(0.0, 1.0) * 0.20 +
                    contrast * 0.30
                ).coerceIn(0.0, 1.0)
            return ProcessedFrame(frame, quality, regions, Instant.now())
        } finally {
            source.release()
            resized.release()
            rgb.release()
            lab.release()
            channels.forEach(Mat::release)
            equalizedL.release()
            enhancedLab.release()
            enhancedRgb.release()
            denoisedRgb.release()
            gray.release()
            mean.release()
            deviation.release()
            hsv.release()
            saturationMask.release()
            edges.release()
            hierarchy.release()
            contours.forEach(MatOfPoint::release)
            clahe.collectGarbage()
        }
    }

    private fun Rect.toPixelRect() = PixelRect(x, y, x + width, y + height)
}
