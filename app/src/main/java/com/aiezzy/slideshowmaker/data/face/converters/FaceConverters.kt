package com.aiezzy.slideshowmaker.data.face.converters

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Room type converters for face-related data types
 */
class FaceConverters {

    /**
     * Convert FloatArray embedding to ByteArray for storage
     */
    @TypeConverter
    fun fromFloatArray(floatArray: FloatArray?): ByteArray? {
        if (floatArray == null) return null
        val buffer = ByteBuffer.allocate(floatArray.size * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(floatArray)
        return buffer.array()
    }

    /**
     * Convert ByteArray back to FloatArray embedding
     */
    @TypeConverter
    fun toFloatArray(byteArray: ByteArray?): FloatArray? {
        if (byteArray == null) return null
        val buffer = ByteBuffer.wrap(byteArray)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floatArray = FloatArray(byteArray.size / 4)
        buffer.asFloatBuffer().get(floatArray)
        return floatArray
    }

    companion object {
        /**
         * Utility to convert FloatArray to ByteArray without Room
         */
        fun floatArrayToByteArray(floatArray: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(floatArray.size * 4)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(floatArray)
            return buffer.array()
        }

        /**
         * Utility to convert ByteArray to FloatArray without Room
         */
        fun byteArrayToFloatArray(byteArray: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(byteArray)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val floatArray = FloatArray(byteArray.size / 4)
            buffer.asFloatBuffer().get(floatArray)
            return floatArray
        }
    }
}
