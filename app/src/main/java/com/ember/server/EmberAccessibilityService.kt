package com.ember.server

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

class EmberAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var server: EmberWsServer? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        val port = getSharedPreferences("config", MODE_PRIVATE).getInt("port", 8765)
        server = EmberWsServer(port) { conn, msg -> handler.post { dispatch(conn, msg) } }
        server?.start()
        Log.i(TAG, "Started on ws://0.0.0.0:$port")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private fun dispatch(conn: WebSocket, msg: JSONObject) {
        val id = msg.getInt("id")
        try {
            when (val action = msg.getString("action")) {
                "tap" -> {
                    val path = Path().apply {
                        moveTo(msg.getDouble("x").toFloat(), msg.getDouble("y").toFloat())
                    }
                    dispatchGesture(conn, id, path, duration = 1)
                }
                "swipe" -> {
                    val path = Path().apply {
                        moveTo(msg.getDouble("x_start").toFloat(), msg.getDouble("y_start").toFloat())
                        lineTo(msg.getDouble("x_end").toFloat(),   msg.getDouble("y_end").toFloat())
                    }
                    dispatchGesture(conn, id, path, duration = msg.optLong("duration", 300))
                }
                "input_text" -> {
                    val input = findEditText(rootInActiveWindow)
                        ?: throw RuntimeException("EditText not found")
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        msg.getString("text")
                    )
                    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    reply(conn, id)
                }
                "key_event"  -> handleKeyEvent(conn, id, msg.getString("keycode"))
                "dump_ui"    -> reply(conn, id, buildXml(rootInActiveWindow))
                "screenshot" -> captureScreenshot(conn, id)
                else -> throw RuntimeException("Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispatch error: ${e.message}")
            replyError(conn, id, e.message ?: "unknown")
        }
    }

    // ── Gestures ──────────────────────────────────────────────────────────────

    private fun dispatchGesture(conn: WebSocket, id: Int, path: Path, duration: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(desc, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) = reply(conn, id)
            override fun onCancelled(g: GestureDescription) = replyError(conn, id, "Gesture cancelled")
        }, handler)
    }

    // ── Key events ────────────────────────────────────────────────────────────

    private fun handleKeyEvent(conn: WebSocket, id: Int, keycode: String) {
        val ok = when (keycode.uppercase()) {
            "KEYCODE_BACK", "4"   -> performGlobalAction(GLOBAL_ACTION_BACK)
            "KEYCODE_HOME", "3"   -> performGlobalAction(GLOBAL_ACTION_HOME)
            "KEYCODE_ENTER", "66" -> findEditText(rootInActiveWindow)
                ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
            "KEYCODE_CTRL_A" -> {
                val input = findEditText(rootInActiveWindow)
                if (input != null) {
                    val args = Bundle()
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, input.text?.length ?: 0)
                    input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
                } else false
            }
            "KEYCODE_DEL", "67" -> {
                val input = findEditText(rootInActiveWindow)
                if (input != null) {
                    val args = Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } else false
            }
            else -> false
        }
        if (ok) reply(conn, id) else replyError(conn, id, "Unsupported keycode: $keycode")
    }

    // ── Screenshot ────────────────────────────────────────────────────────────

    private fun captureScreenshot(conn: WebSocket, id: Int) {
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    if (bmp == null) { replyError(conn, id, "Bitmap conversion failed"); return }
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    reply(conn, id, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                }
                override fun onFailure(errorCode: Int) =
                    replyError(conn, id, "Screenshot failed: $errorCode")
            }
        )
    }

    // ── UI tree → XML ─────────────────────────────────────────────────────────

    private fun buildXml(node: AccessibilityNodeInfo?): String {
        if (node == null) return "<hierarchy />"
        return buildString {
            append("<hierarchy>")
            appendNode(this, node)
            append("</hierarchy>")
        }
    }

    private fun appendNode(sb: StringBuilder, node: AccessibilityNodeInfo) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        sb.append("<node")
        sb.append(" class=\"${esc(node.className?.toString())}\"")
        sb.append(" text=\"${esc(node.text?.toString())}\"")
        sb.append(" resource-id=\"${esc(node.viewIdResourceName)}\"")
        sb.append(" content-desc=\"${esc(node.contentDescription?.toString())}\"")
        sb.append(" clickable=\"${node.isClickable}\"")
        sb.append(" enabled=\"${node.isEnabled}\"")
        sb.append(" bounds=\"[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]\"")
        sb.append(">")
        for (i in 0 until node.childCount) node.getChild(i)?.let { appendNode(sb, it) }
        sb.append("</node>")
    }

    private fun esc(s: String?) = (s ?: "")
        .replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.contains("EditText") == true && node.isEnabled) return node
        for (i in 0 until node.childCount) findEditText(node.getChild(i))?.let { return it }
        return null
    }

    private fun reply(conn: WebSocket, id: Int, result: Any? = null) {
        val json = JSONObject().put("id", id)
        if (result != null) json.put("result", result) else json.put("result", JSONObject.NULL)
        conn.send(json.toString())
    }

    private fun replyError(conn: WebSocket, id: Int, error: String) {
        conn.send(JSONObject().put("id", id).put("error", error).toString())
    }

    companion object {
        private const val TAG = "EmberServer"
    }
}

// ── WebSocket server ──────────────────────────────────────────────────────────

private class EmberWsServer(
    port: Int,
    private val onCmd: (WebSocket, JSONObject) -> Unit,
) : WebSocketServer(InetSocketAddress(port)) {

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.i("EmberWsServer", "connected: ${conn.remoteSocketAddress}")
    }
    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.i("EmberWsServer", "disconnected")
    }
    override fun onMessage(conn: WebSocket, message: String) {
        try { onCmd(conn, JSONObject(message)) } catch (e: Exception) {
            Log.e("EmberWsServer", "parse error: ${e.message}")
        }
    }
    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("EmberWsServer", "error: ${ex.message}")
    }
    override fun onStart() {}
}
