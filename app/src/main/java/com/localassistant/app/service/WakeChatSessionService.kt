package com.localassistant.app.service

import android.app.Activity
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
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
 * The key the wake service uses to say which half of the exchange the user is in.
 * The window is raised the moment the wake phrase is matched, before any words
 * exist, so a session has to be able to render an exchange that has no text yet.
 */
const val SESSION_STATE_KEY = "state"

/** The phrase was heard; the app is waiting for the request. */
const val SESSION_STATE_LISTENING = "listening"

/** The request and the reply are both known. */
const val SESSION_STATE_REPLY = "reply"

/**
 * The card colours, matching the app's Gruvbox dark scheme. A session is drawn
 * by the system, so it cannot read [Theme] and has to name them itself.
 */
private const val SURFACE = 0xFF2B2B2B.toInt()

private const val MUTED = 0xFFAAAAAA.toInt()

private const val PRIMARY = 0xFFF2A94C.toInt()

private const val ON_PRIMARY = 0xFF1B1B12.toInt()

private const val USER_BUBBLE = 0xFF3A3A3A.toInt()

private const val REPLY_BUBBLE = 0xFF342A1F.toInt()

/**
 * Where the card sits inside the window the system gives a session.
 *
 * This is [android.ui.Gravity]'s BOTTOM (0x2) combined with its MIDDLE (0x20),
 * which centres horizontally. The package itself is not reachable from this
 * Kotlin toolchain - `android.ui` is unresolved - so the bits are spelled out
 * here rather than named.
 */
private const val BOTTOM_CENTER_GRAVITY = 0x2 or 0x20

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
     * Create the session that will show the exchange.
     *
     * The session is built around this service as its context, and that is the
     * only context that can work here. [VoiceInteractionSession] starts by asking
     * the context for a window context of type TYPE_VOICE_INTERACTION
     * (createWindowContextIfNeeded), and it does that only when the context is
     * NOT a UI context: an android.app.Activity is annotated @UiContext, so
     * handing one over skips that step entirely and the session's window is
     * attached to a surface that belongs to nothing, which is why the exchange
     * never appears over the home screen or another app. A Service is a
     * ContextWrapper - hence a Context - whose base is null, so it reports
     * isUiContext() false and can answer getSystemService(DisplayManager),
     * which is exactly what the window context needs. AOSP hands this same
     * object to HandlerCaller in onCreate() for the same reason.
     */
    override fun onNewSession(args: android.os.Bundle): VoiceInteractionSession {
        Log.i("WakeChatSessionService", "The system asked for a session: $args")
        return WakeChatSession(this)
    }
}

/**
 * A voice interaction session that shows the wake exchange the system asked for.
 *
 * The session is created before the system knows what the user said, so the
 * request and the reply are captured in [onPrepareShow] and only turned into
 * views in [onCreateContentView], which the framework calls afterwards.
 *
 * The context is the session service itself, never an [android.app.Activity]:
 * Activity is annotated @UiContext, which makes
 * [VoiceInteractionSession.createWindowContextIfNeeded][VoiceInteractionSession]
 * keep the context as it is and attach the session window to a surface that
 * belongs to nothing. A Service is a ContextWrapper with no base, so it reports
 * isUiContext() false and the session gets a real TYPE_VOICE_INTERACTION window
 * context from the DisplayManager, which is the surface drawn over every app.
 */
private class WakeChatSession(private val context: android.app.Service) : VoiceInteractionSession(context) {

    private var state = ""
    private var request = ""
    private var reply = ""

    /**
     * Take the exchange the wake service put in the bundle. Called before the
     * content view is created, which is why the text is captured here rather
     * than read where the views are built.
     */
    override fun onPrepareShow(args: android.os.Bundle, showFlags: Int) {
        state = args.getString(SESSION_STATE_KEY) ?: ""
        request = args.getString(SESSION_REQUEST_KEY) ?: ""
        reply = args.getString(SESSION_REPLY_KEY) ?: ""
    }

    /**
     * Build the card the system will draw over the screen.
     *
     * The root is deliberately left unpainted and bottom-centred: the system
     * stretches a session's content view over the whole window it owns, so a
     * background here would cover every pixel and hide whatever the device is
     * showing - the complaint the user sees as "it's fullscreen". Only the card
     * itself is painted, which is what makes the exchange look like the chat
     * inside the app: a small card at the bottom with one bubble per turn.
     */
    override fun onCreateContentView(): View {
        Log.i(
            "WakeChatSessionService",
            "Building the card for state=$state, a ${request.length}-character request and a ${reply.length}-character reply"
        )
        val stage = LinearLayout(context)
        stage.setGravity(BOTTOM_CENTER_GRAVITY)
        val turns = mutableListOf<LinearLayout>()
        if (request.isNotBlank()) turns.add(bubble(request, USER_BUBBLE, MUTED))
        if (reply.isNotBlank()) turns.add(bubble(reply, REPLY_BUBBLE, PRIMARY))
        stage.addView(
            if (turns.isEmpty()) {
                card(listOf(line("Heard it - go ahead, I'm listening", MUTED, 19f)))
            } else {
                card(turns)
            }
        )
        return stage
    }

    /**
     * A card is the small panel the exchange lives in: Gruvbox surface, generous
     * padding, and the bubbles stacked inside it.
     */
    private fun card(children: Iterable<View>): LinearLayout {
        val panel = LinearLayout(context)
        panel.setPadding(20, 16, 20, 16)
        panel.setBackgroundColor(SURFACE)
        children.forEach { child -> panel.addView(child) }
        return panel
    }

    /**
     * One turn of the exchange as a bubble, the same shape the in-app card draws.
     */
    private fun bubble(text: String, background: Int, textColour: Int): LinearLayout {
        val panel = LinearLayout(context)
        panel.setPadding(14, 10, 14, 10)
        panel.setBackgroundColor(background)
        panel.addView(line(text, textColour, 19f))
        return panel
    }

    private fun line(text: String, color: Int, size: Float): TextView {
        val line = TextView(context)
        line.setText(text)
        line.setTextColor(color)
        line.textSize = size
        return line
    }

    /**
     * The session has no resources of its own to release, but the base class
     * keeps a system window alive for as long as the session object does, so the
     * exchange must be dropped when the session is torn down.
     */
    override fun onDestroy() {
        state = ""
        request = ""
        reply = ""
    }
}
