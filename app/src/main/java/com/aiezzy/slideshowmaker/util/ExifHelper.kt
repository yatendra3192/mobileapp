package com.aiezzy.slideshowmaker.util

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Helper class for extracting EXIF metadata from images.
 * Used for auto-generating captions based on photo date, location, etc.
 */
object ExifHelper {

    data class PhotoMetadata(
        val dateTaken: Date? = null,
        val location: LocationInfo? = null,
        val cameraModel: String? = null,
        val orientation: Int = 0
    )

    data class LocationInfo(
        val latitude: Double,
        val longitude: Double,
        val placeName: String? = null
    )

    /**
     * Extract metadata from an image URI.
     */
    suspend fun extractMetadata(context: Context, imageUri: Uri): PhotoMetadata = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(imageUri)
            if (inputStream == null) return@withContext PhotoMetadata()

            val exif = ExifInterface(inputStream)

            // Extract date
            val dateTaken = extractDate(exif)

            // Extract location
            val location = extractLocation(context, exif)

            // Extract camera info
            val cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)

            // Extract orientation
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            PhotoMetadata(
                dateTaken = dateTaken,
                location = location,
                cameraModel = cameraModel,
                orientation = orientation
            )
        } catch (e: Exception) {
            e.printStackTrace()
            PhotoMetadata()
        } finally {
            inputStream?.close()
        }
    }

    /**
     * Generate a smart caption based on photo metadata.
     */
    fun generateCaption(metadata: PhotoMetadata, style: CaptionStyle = CaptionStyle.FULL): String {
        val parts = mutableListOf<String>()

        when (style) {
            CaptionStyle.DATE_ONLY -> {
                metadata.dateTaken?.let { date ->
                    parts.add(formatDate(date, DateFormat.FRIENDLY))
                }
            }
            CaptionStyle.LOCATION_ONLY -> {
                metadata.location?.placeName?.let { place ->
                    parts.add(place)
                }
            }
            CaptionStyle.FULL -> {
                metadata.location?.placeName?.let { place ->
                    parts.add(place)
                }
                metadata.dateTaken?.let { date ->
                    parts.add(formatDate(date, DateFormat.FRIENDLY))
                }
            }
            CaptionStyle.MINIMAL -> {
                metadata.dateTaken?.let { date ->
                    parts.add(formatDate(date, DateFormat.SHORT))
                }
            }
        }

        return parts.joinToString(" • ")
    }

    /**
     * Generate captions for multiple images.
     * Groups images by date/location for smarter captions.
     */
    suspend fun generateSmartCaptions(
        context: Context,
        imageUris: List<Uri>,
        style: CaptionStyle = CaptionStyle.FULL
    ): List<String> = withContext(Dispatchers.IO) {
        val metadataList = imageUris.map { extractMetadata(context, it) }
        val captions = mutableListOf<String>()

        // Group consecutive images by date (same day)
        var previousDate: String? = null

        for (metadata in metadataList) {
            val currentDate = metadata.dateTaken?.let { formatDate(it, DateFormat.DAY_KEY) }

            val caption = when {
                // First image or new date group - show full caption
                previousDate == null || currentDate != previousDate -> {
                    generateCaption(metadata, style)
                }
                // Same date group - show minimal or empty
                style == CaptionStyle.FULL -> {
                    // Show location if different, otherwise empty
                    metadata.location?.placeName ?: ""
                }
                else -> ""
            }

            captions.add(caption)
            previousDate = currentDate
        }

        captions
    }

    private fun extractDate(exif: ExifInterface): Date? {
        // Try different date tags
        val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME)
        val dateDigitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)

        val dateString = dateTimeOriginal ?: dateTime ?: dateDigitized ?: return null

        return try {
            val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            format.parse(dateString)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractLocation(context: Context, exif: ExifInterface): LocationInfo? {
        val latLong = exif.latLong ?: return null
        val latitude = latLong[0]
        val longitude = latLong[1]

        // Try to get place name using Geocoder
        val placeName = try {
            @Suppress("DEPRECATION")
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                // Build a readable location string
                val city = address.locality ?: address.subAdminArea
                val country = address.countryName
                when {
                    city != null && country != null -> "$city, $country"
                    city != null -> city
                    country != null -> country
                    else -> null
                }
            } else null
        } catch (e: Exception) {
            null
        }

        return LocationInfo(latitude, longitude, placeName)
    }

    private fun formatDate(date: Date, format: DateFormat): String {
        val formatter = when (format) {
            DateFormat.FRIENDLY -> {
                // "January 15, 2024"
                SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
            }
            DateFormat.SHORT -> {
                // "Jan 2024"
                SimpleDateFormat("MMM yyyy", Locale.getDefault())
            }
            DateFormat.DAY_KEY -> {
                // For grouping: "2024-01-15"
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            }
            DateFormat.YEAR_ONLY -> {
                SimpleDateFormat("yyyy", Locale.getDefault())
            }
        }
        return formatter.format(date)
    }

    enum class CaptionStyle {
        FULL,           // Location + Date
        DATE_ONLY,      // Just the date
        LOCATION_ONLY,  // Just the location
        MINIMAL         // Short date only
    }

    private enum class DateFormat {
        FRIENDLY,
        SHORT,
        DAY_KEY,
        YEAR_ONLY
    }
}
