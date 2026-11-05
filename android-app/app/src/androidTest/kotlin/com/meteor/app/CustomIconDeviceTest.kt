package com.meteor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meteor.app.data.CustomIcons
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CustomIconDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun importedImageIsReducedToABoundedPrivatePng() = runBlocking {
        val source = File(context.cacheDir, "instrumentation-icon.png")
        val icons = CustomIcons(context)
        var saved: File? = null
        try {
            val original = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
            try { source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { original.recycle() }
            saved = icons.file(icons.import(Uri.fromFile(source)))
            assertNotNull(saved)
            val image = requireNotNull(BitmapFactory.decodeFile(requireNotNull(saved).absolutePath))
            try {
                assertEquals(512, image.width)
                assertEquals(256, image.height)
            } finally { image.recycle() }
        } finally { source.delete(); saved?.delete() }
    }

    @Test fun replacedImportedIconFileCanBeDeleted() = runBlocking {
        val source = File(context.cacheDir, "instrumentation-replaced-icon.png")
        val icons = CustomIcons(context)
        try {
            val original = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try { source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { original.recycle() }
            val token = icons.import(Uri.fromFile(source))
            val saved = requireNotNull(icons.file(token))
            assertTrue(saved.exists())
            assertTrue(icons.delete(token))
            assertFalse(saved.exists())
            assertFalse("Built-in icon names are not files", icons.delete("meteor"))
            assertFalse("Tokens cannot escape the icon directory", icons.delete("file:../../profiles.secured"))
        } finally { source.delete() }
    }

    @Test fun corruptAndOversizedImagesFailWithoutAUsableIcon() = runBlocking {
        val source = File(context.cacheDir, "instrumentation-invalid-icon.bin")
        val icons = CustomIcons(context)
        try {
            source.writeText("This is not an image.")
            assertTrue(runCatching { icons.import(Uri.fromFile(source)) }.isFailure)
            source.writeBytes(ByteArray(3 * 1024 * 1024 + 1))
            assertTrue(runCatching { icons.import(Uri.fromFile(source)) }.isFailure)
            assertNull(icons.file("file:../../profiles.secured"))
        } finally { source.delete() }
    }
}
