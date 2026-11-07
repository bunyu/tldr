package dev.tldr.app

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

data class ScreenCapture(
    val text: String,
    val packageName: String?,
    val appLabel: String?,
    /** Address bar contents when the foreground app is a known browser. */
    val url: String?,
)

/** Walks the accessibility tree of the app windows on screen and collects their visible text. */
object ScreenTextExtractor {

    private const val MAX_NODES = 5_000

    /** View ids of address bars in common browsers. */
    private val URL_BAR_IDS = listOf(
        "com.android.chrome:id/url_bar",
        "com.chrome.beta:id/url_bar",
        "com.brave.browser:id/url_bar",
        "com.microsoft.emmx:id/url_bar",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "com.opera.browser:id/url_field",
        "com.coccoc.trinhduyet:id/url_bar",
    )

    fun capture(service: AccessibilityService): ScreenCapture {
        val ownPackage = service.packageName
        val roots = buildList {
            // Top-most first, so the main app comes before anything behind it.
            service.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .filter { it.packageName?.toString() != ownPackage }
                .forEach { add(it) }
            if (isEmpty()) {
                service.rootInActiveWindow
                    ?.takeIf { it.packageName?.toString() != ownPackage }
                    ?.let { add(it) }
            }
        }

        val lines = ArrayList<String>()
        var url: String? = null
        for (root in roots) {
            if (url == null) url = findUrl(root)
            collectText(root, lines)
        }

        val pkg = roots.firstOrNull()?.packageName?.toString()
        return ScreenCapture(
            text = lines.joinToString("\n"),
            packageName = pkg,
            appLabel = pkg?.let { appLabel(service.packageManager, it) },
            url = url,
        )
    }

    /**
     * Buttons, text fields, checkboxes and tabs are app UI, not content. Some apps wrap a whole
     * post in a "button", so only short button labels are skipped. (Tappable text such as an
     * author's name is kept; button-like words are removed later by ContentPrep.)
     */
    private fun isUiControl(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        if (cls.endsWith("EditText") || cls.endsWith("CheckBox") || cls.endsWith("Switch") ||
            cls.endsWith("TabWidget") || cls.endsWith("SeekBar")
        ) return true
        val label = (node.text ?: node.contentDescription)?.length ?: 0
        return cls.endsWith("Button") && label < 40
    }

    private fun collectText(root: AccessibilityNodeInfo, out: MutableList<String>) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (!node.isVisibleToUser) continue
            val skipOwnText = isUiControl(node)

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            when {
                skipOwnText -> Unit
                text.isNotEmpty() -> out.add(text)
                // Many apps (Facebook included) put post text in the content description.
                desc.length > 1 -> out.add(desc)
            }

            // Push children in reverse so they pop in reading order.
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
    }

    private fun findUrl(root: AccessibilityNodeInfo): String? {
        for (id in URL_BAR_IDS) {
            val raw = root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull()?.text?.toString()?.trim()
            if (!raw.isNullOrEmpty() && !raw.contains(' ')) {
                return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            }
        }
        return null
    }

    private fun appLabel(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
