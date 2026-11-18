package com.example.second_screen_viewer

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ControlServiceManifestTest {
    @Test
    fun controlHttpServiceUsesSpecialUseForegroundServiceDeclaration() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val manifest = documentBuilderFactory.newDocumentBuilder().parse(manifestFile)
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val services = manifest.getElementsByTagName("service")
        val controlService = (0 until services.length)
            .map { services.item(it) as Element }
            .firstOrNull { it.getAttributeNS(androidNamespace, "name") == ".ControlHttpService" }

        assertNotNull("ControlHttpService must be declared in the source manifest", controlService)
        val service = requireNotNull(controlService)
        assertEquals(
            "ControlHttpService should use the specialUse foreground service type",
            "specialUse",
            service.getAttributeNS(androidNamespace, "foregroundServiceType"),
        )

        val permissions = manifest.getElementsByTagName("uses-permission")
        val permissionNames = (0 until permissions.length)
            .map { permissions.item(it) as Element }
            .map { it.getAttributeNS(androidNamespace, "name") }
            .toSet()

        assertTrue(
            "The general foreground-service permission must remain declared",
            "android.permission.FOREGROUND_SERVICE" in permissionNames,
        )
        assertTrue(
            "The special-use foreground-service permission must be declared",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in permissionNames,
        )
        assertFalse(
            "The unused data-sync foreground-service permission should be removed",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in permissionNames,
        )

        val properties = service.getElementsByTagName("property")
        val subtypeProperty = (0 until properties.length)
            .map { properties.item(it) as Element }
            .firstOrNull {
                it.getAttributeNS(androidNamespace, "name") ==
                    "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            }

        assertNotNull("The service must explain its special-use foreground-service subtype", subtypeProperty)
        assertTrue(
            "The special-use foreground-service subtype must not be blank",
            requireNotNull(subtypeProperty).getAttributeNS(androidNamespace, "value").isNotBlank(),
        )
    }
}
