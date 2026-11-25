package app.kai.photomark

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionPolicyTest {
    @Test fun photoLibraryRequestsOnlyImageAccessForTheOperatingSystem() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), libraryReadPermissions(32))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), libraryReadPermissions(33))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            libraryReadPermissions(34))
    }

    @Test fun originalLocationOnlyAppliesToIndexedPhotos() {
        for (uri in listOf("content://media/external_primary/images/media/42", "content://media/ABCD-1234/file/7")) {
            assertTrue(uri, isIndexedPhoto(Uri.parse(uri)))
        }
        for (uri in listOf("content://media/picker/0/com.android.providers.media.photopicker/media/42",
            "content://media/picker_get_content/0/com.android.providers.media.photopicker/media/42",
            "content://com.android.providers.media.documents/document/image%3A42",
            "content://media/external/video/media/42", "file:///data/user/0/works/original.jpg")) {
            assertFalse(uri, isIndexedPhoto(Uri.parse(uri)))
        }
    }

    @Test fun distributedFlavorsDeclareTheirIntendedAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertFalse(Manifest.permission.MANAGE_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.READ_MEDIA_VIDEO in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        val library = BuildConfig.FLAVOR == "library"
        for (permission in listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
            // Android may add READ_MEDIA_VISUAL_USER_SELECTED automatically to a merged media-reader manifest.
            assertTrue(permission, (permission in permissions) == library)
        }
    }
}
