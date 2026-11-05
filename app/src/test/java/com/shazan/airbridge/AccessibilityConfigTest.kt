package com.shazan.airbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression guard for the core accessibility service (AirBridgeInputService). Its profile must stay
 * gestures-only: any widening switches the service off on users' phones after an update and makes
 * banking/UPI apps flag AirBridge as a screen reader. Screen-content features belong in
 * AirBridgeTextService (res/xml/text_service_config.xml).
 */
class AccessibilityConfigTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun load(name: String): Element {
        // Gradle runs unit tests from the module directory; IDE runners may use the project root.
        val file = listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name").map(::File).firstOrNull { it.isFile }
            ?: error("res/xml/$name not found from ${File(".").absolutePath}")
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = f.newDocumentBuilder().parse(file).documentElement
        assertEquals("accessibility-service", root.tagName)
        return root
    }

    private fun Element.attrs(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (i in 0 until attributes.length) {
            val a = attributes.item(i)
            if (a.namespaceURI == ns) m[a.localName] = a.nodeValue
        }
        return m
    }

    private fun flags(v: String?): Set<String> = v?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    @Test fun coreServiceStaysGesturesOnly() {
        val a = load("accessibility_service_config.xml").attrs()
        for (cap in listOf("canRetrieveWindowContent", "canRequestFilterKeyEvents", "canRequestTouchExplorationMode",
            "canRequestEnhancedWebAccessibility", "canRequestFingerprintGestures", "canTakeScreenshot", "canControlMagnification")) {
            if (cap in a && a[cap] != "false") fail("core accessibility service must not declare $cap (move it to AirBridgeTextService)")
        }
        if ("accessibilityEventTypes" in a) fail("core accessibility service must not subscribe to any accessibilityEventTypes (found ${a["accessibilityEventTypes"]})")
        val banned = setOf(
            "flagRetrieveInteractiveWindows", "flagInputMethodEditor", "flagRequestTouchExplorationMode",
            "flagRequestFilterKeyEvents", "flagIncludeNotImportantViews", "flagReportViewIds",
            "flagRequestEnhancedWebAccessibility", "flagRequestAccessibilityButton", "flagRequestFingerprintGestures",
        )
        val f = flags(a["accessibilityFlags"])
        val bad = f intersect banned
        assertTrue("core accessibility service must not declare $bad", bad.isEmpty())
        assertEquals(setOf("flagDefault"), f)
        assertEquals("true", a["canPerformGestures"])
        assertEquals("feedbackGeneric", a["accessibilityFeedbackType"])
        assertNotNull(a["description"])
        assertFalse("we are not a disability tool: never claim isAccessibilityTool", "isAccessibilityTool" in a)
    }

    @Test fun coreServiceDeclaresNothingElse() {
        val allowed = setOf("accessibilityFeedbackType", "accessibilityFlags", "canPerformGestures", "description", "notificationTimeout")
        val extra = load("accessibility_service_config.xml").attrs().keys - allowed
        assertTrue("unexpected attributes on the core accessibility service: $extra", extra.isEmpty())
    }

    @Test fun textServiceIsMinimal() {
        val a = load("text_service_config.xml").attrs()
        assertEquals(setOf("typeViewClicked", "typeWindowStateChanged"), flags(a["accessibilityEventTypes"]))
        assertEquals(setOf("flagDefault", "flagInputMethodEditor", "flagRetrieveInteractiveWindows"), flags(a["accessibilityFlags"]))
        assertEquals("true", a["canRetrieveWindowContent"])
        for (k in listOf("canPerformGestures", "canRequestFilterKeyEvents", "canRequestTouchExplorationMode", "isAccessibilityTool")) {
            assertFalse("text service must not declare $k", k in a)
        }
    }
}
