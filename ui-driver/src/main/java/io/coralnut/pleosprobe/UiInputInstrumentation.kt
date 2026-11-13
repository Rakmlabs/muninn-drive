package io.coralnut.pleosprobe
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
/** Unicode input in the actual foreground UI; runs in its own test APK process. */
class UiInputInstrumentation : Instrumentation() {
    private var input = ""
    private var mode = ""
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments); input = arguments?.getString("text") ?: ""; mode=arguments?.getString("mode") ?: ""; start()
    }
    override fun onStart() {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        try {
            if(mode=="dump") {
                val info=uiAutomation.serviceInfo
                info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                uiAutomation.serviceInfo=info
                val writer=java.io.StringWriter()
                val xml=android.util.Xml.newSerializer();xml.setOutput(writer);xml.startTag(null,"hierarchy")
                fun write(node:AccessibilityNodeInfo?){
                    if(node==null)return
                    xml.startTag(null,"node")
                    xml.attribute(null,"text",node.text?.toString() ?: "")
                    xml.attribute(null,"content-desc",node.contentDescription?.toString() ?: "")
                    xml.attribute(null,"package",node.packageName?.toString() ?: "")
                    xml.attribute(null,"enabled",node.isEnabled.toString())
                    val r=android.graphics.Rect();node.getBoundsInScreen(r)
                    xml.attribute(null,"bounds","[${r.left},${r.top}][${r.right},${r.bottom}]")
                    for(i in 0 until node.childCount)write(node.getChild(i))
                    xml.endTag(null,"node")
                }
                uiAutomation.windows.forEach{write(it.root)}
                xml.endTag(null,"hierarchy");xml.flush()
                finish(Activity.RESULT_OK,Bundle().apply{putString("stream","WINDOWS_XML $writer\n")});return
            }
            val node = find(uiAutomation.rootInActiveWindow) ?: error("No editable foreground field")
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
            })) { "Text action rejected" }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "UI_TEXT_ENTERED $input\n") })
        } catch (e: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "UI_INPUT_FAILED ${e.message}\n") })
        }
    }
}
