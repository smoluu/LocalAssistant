package com.localassistant.app.service

import android.app.Activity
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The key the wake service uses to hand the user's request to a session.
 *
 * The two halves of the pipeline never share memory, so the exchange travels
 * under these keys in the bundle the wake service passes to
 * [VoiceInteractionService.showSession][showSession].
 */
const val SESSION_REQUEST_KEY = "request"

/**
 * The key the wake service uses to hand the spoken reply to a session.
 */
const val SESSION_REPLY_KEY = "reply"

/**
 * The card colours, matching the app's Gruvbox dark scheme. A session is drawn
 * by the system, so it cannot read [Theme] and has to name them itself.
 */
private const val SURFACE = 0xFF2B2B2B.toInt()

private const val MUTED = 0xFFAAAAAA.toInt()

private const val PRIMARY = 0xFFF2A94C.toInt()

/**
 * The session half of the wake pipeline.
 *
 * A [VoiceInteractionService] can keep LocalAssistant listening while the app is
 * minimized, but everything it can show the user in that state travels in a
 * notification - the app's own Compose overlay only paints inside the app's
 * surface, so it is invisible on the home screen or over another app.
 *
 * A [VoiceInteractionSession] is the opposite half: the system draws it as a
 * window of type TYPE_VOICE_INTERACTION over whatever is on screen, which is the
 * only way the wake chat can be seen outside the app. The two halves never
 * share memory - the session runs in its own process - so the exchange travels
 * in the [Bundle] the wake service passes to
 * [VoiceInteractionService.showSession][showSession].
 *
 * The content view is built from the legacy Java UI classes ([LinearLayout],
 * [TextView]) because that is what a session window accepts:
 * [VoiceInteractionSession.onCreateContentView][onCreateContentView] returns an
 * [android.view.View], not a Compose surface.
 */
class WakeChatSessionService : VoiceInteractionSessionService() {

    /**
     * Create the session that will show the exchange. The text itself is not
     * available yet - it arrives in [onPrepareShow] - so the session is built
     * around a context the widgets can be measured against.
     */
    override fun onNewSession(args: android.os.Bundle): VoiceInteractionSession {
        return WakeChatSession(android.app.Activity())
    }
}

/**
 * A voice interaction session that shows the wake exchange the system asked for.
 *
 * The session is created before the system knows what the user said, so the
 * request and the reply are captured in [onPrepareShow] and only turned into
 * views in [onCreateContentView], which the framework calls afterwards.
 */
private class WakeChatSession(private val context: android.app.Activity) : VoiceInteractionSession(context) {

    private var request = ""
    private var reply = ""

    /**
     * Take the exchange the wake service put in the bundle. Called before the
     * content view is created, which is why the text is captured here rather
     * than read where the views are built.
     */
    override fun onPrepareShow(args: android.os.Bundle, showFlags: Int) {
        request = args.getString(SESSION_REQUEST_KEY) ?: ""
        reply = args.getString(SESSION_REPLY_KEY) ?: ""
    }

    /**
     * Build the card the system will draw over the screen: the request the user
     * spoke, then the reply, in the app's Gruvbox colours.
     */
    override fun onCreateContentView(): View {
        val card = LinearLayout(context)
        card.setPadding(24, 20, 24, 20)
        card.setBackgroundColor(SURFACE)
        if (request.isNotBlank()) {
            card.addView(requestLine(request))
        }
        card.addView(replyLine(reply))
        return card
    }

    private fun requestLine(text: String): TextView {
        val line = TextView(context)
        line.setText(text)
        line.setTextColor(MUTED)
        line.textSize = 17f
        return line
    }

    private fun replyLine(text: String): TextView {
        val line = TextView(context)
        line.setText(text)
        line.setTextColor(PRIMARY)
        line.textSize = 21f
        return line
    }

    /**
     * The session has no resources of its own to release, but the base class
     * keeps a system window alive for as long as the session object does, so the
     * exchange must be dropped when the session is torn down.
     */
    override fun onDestroy() {
        request = ""
        reply = ""
    }
}
