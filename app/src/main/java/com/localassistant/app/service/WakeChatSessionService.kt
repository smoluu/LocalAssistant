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

/** The request is known and the answer is being produced. */
const val SESSION_STATE_REQUEST = "request"

/** The request and the reply are both known. */
const val SESSION_STATE_REPLY = "reply"

/**
 * The exchange has been closed, so the session is asked to show nothing and to
 * tear its window down.
 */
const val SESSION_STATE_CLOSE = "close"

/** The card text the platform shows while it waits for the request. */
const val SESSION_LISTENING_TEXT = "Heard it - go ahead, I'm listening"

/**
 * The card colours, matching the app's Gruvbox dark scheme. A session is drawn
 * by the system, so it cannot read [Theme] and has to name them itself.
 */
private const val TRANSPARENT = 0x00000000

private const val SURFACE = 0xFF2B2B2B.toInt()

private const val MUTED = 0xFFAAAAAA.toInt()

private const val REPLY_TEXT = 0xFFF2A94C.toInt()

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
 * The size the stage is forced to before the card is added to it.
 *
 * The window is `(0,0)(fillxfill)`, i.e. the whole screen, but a
 * `LinearLayout` measures to its content, so without a forced size the stage is
 * exactly as large as the card and `setGravity` has nothing to position within -
 * the card then lands in the window's top-left corner, which is what the user
 * sees when the exchange is raised. Forcing a size far beyond any display makes
 * the measure pass clamp the stage to the space the window offers, which is the
 * screen, and gravity can bottom-centre the card in it.
 *
 * There is no other way to fill the stage here: `setFillDimensions` does not
 * exist in this toolchain, `android.widget.internal` (the package holding a
 * `Filler` spacer) is unreachable, and no accessor for the display size
 * (`android.graphics.DisplayMetrics`, `getDisplayMetrics`) resolves either.
 */
private const val STAGE_MINIMUM = 99999

/**
 * How much text the card can show.
 *
 * A legacy `TextView` never wraps, so a reply is split into lines here and each
 * line gets one of a fixed pool of TextViews - the pool is fixed because the
 * session builds its content view once and reuses it for every later show, so
 * the structure cannot grow with the text.
 */
private const val MAX_LINES = 6
private const val LINE_CHARS = 48

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
 * The context is the session service itself, never an [android.app.Activity]:
 * Activity is annotated @UiContext, which makes
 * [VoiceInteractionSession.createWindowContextIfNeeded][VoiceInteractionSession]
 * keep the context as it is and attach the session window to a surface that
 * belongs to nothing. A Service is a ContextWrapper with no base, so it reports
 * isUiContext() false and the session gets a real TYPE_VOICE_INTERACTION window
 * context from the DisplayManager, which is the surface drawn over every app.
 *
 * The views are built once, in [onCreateContentView], and every later show only
 * mutates them through [paint], because the framework creates the content view
 * the first time it shows a session and hands that same view to its window
 * afterwards - building fresh views per show would leave the reply never drawn.
 */
private class WakeChatSession(private val context: android.app.Service) : VoiceInteractionSession(context) {

    private var state = ""
    private var request = ""
    private var reply = ""

    /**
     * The views the window shows, built once here and only ever mutated after.
     *
     * An empty `TextView` measures nothing and a transparent background paints
     * nothing, so a state that shows no turn contributes no height and no ink.
     */
    private val stage = LinearLayout(context)
    private val card = LinearLayout(context)
    private val notice = TextView(context)
    private val userBubble = LinearLayout(context)
    private val userLines = (0 until MAX_LINES).map { _ -> TextView(context) }
    private val replyBubble = LinearLayout(context)
    private val replyLines = (0 until MAX_LINES).map { _ -> TextView(context) }

    /**
     * Records what the platform is about to show.
     *
     * AOSP calls this before it asks for the content view, so the text has to be
     * taken here rather than read where the views are built.
     */
    override fun onPrepareShow(args: android.os.Bundle, showFlags: Int) {
        state = args.getString(SESSION_STATE_KEY) ?: ""
        request = args.getString(SESSION_REQUEST_KEY) ?: ""
        reply = args.getString(SESSION_REPLY_KEY) ?: ""
        paint()
    }

    /**
     * Wires the card once and returns the stage that fills the window.
     *
     * The structure is wired here and never again; only [paint] changes what is
     * drawn. Painting again after wiring is what makes the first exchange show
     * the text that [onPrepareShow] already recorded, and it is harmless on
     * every later show because painting is idempotent.
     *
     * The stage is deliberately left unpainted: the system stretches a session's
     * content view over the whole window it owns, so a background here would
     * cover every pixel and hide whatever the device is showing - the complaint
     * the user sees as "it's fullscreen". Only the card itself is painted.
     */
    override fun onCreateContentView(): View {
        Log.i(
            "WakeChatSessionService",
            "Wiring the card for state=$state, a ${request.length}-character request and a ${reply.length}-character reply"
        )
        stage.setGravity(BOTTOM_CENTER_GRAVITY)
        stage.setMinimumWidth(STAGE_MINIMUM)
        stage.setMinimumHeight(STAGE_MINIMUM)
        stage.addView(card)
        card.setPadding(20, 16, 20, 16)
        card.addView(notice)
        notice.textSize = 19f
        notice.setTextColor(MUTED)
        card.addView(userBubble)
        userBubble.setPadding(14, 10, 14, 10)
        userLines.forEach { line ->
            userBubble.addView(line)
            line.textSize = 19f
            line.setTextColor(MUTED)
        }
        card.addView(replyBubble)
        replyBubble.setPadding(14, 10, 14, 10)
        replyLines.forEach { line ->
            replyBubble.addView(line)
            line.textSize = 19f
            line.setTextColor(REPLY_TEXT)
        }
        paint()
        return stage
    }

    /**
     * Draws what [state] asks for, leaving everything else empty.
     *
     * An empty `TextView` measures nothing and a transparent background paints
     * nothing, so a state that shows no turn contributes no height and no ink -
     * which is how the closed exchange looks like nothing at all.
     */
    private fun paint() {
        notice.text = ""
        userLines.forEach { line -> line.text = "" }
        replyLines.forEach { line -> line.text = "" }
        card.setBackgroundColor(if (state == SESSION_STATE_CLOSE) TRANSPARENT else SURFACE)
        userBubble.setBackgroundColor(if (request.isBlank()) TRANSPARENT else USER_BUBBLE)
        replyBubble.setBackgroundColor(if (reply.isBlank()) TRANSPARENT else REPLY_BUBBLE)
        if (state == SESSION_STATE_LISTENING) notice.text = SESSION_LISTENING_TEXT
        wrap(request).forEachIndexed { index, line ->
            if (index < userLines.size) userLines[index].text = line
        }
        wrap(reply).forEachIndexed { index, line ->
            if (index < replyLines.size) replyLines[index].text = line
        }
    }

    /**
     * Tears the window down when the exchange has been closed.
     *
     * The close is requested by [WakeChatService] through
     * [SESSION_STATE_CLOSE], which [paint] already draws as nothing, so the
     * window is invisible even if finishing it only hides it.
     */
    override fun onShow(args: android.os.Bundle?, showFlags: Int) {
        if (state == SESSION_STATE_CLOSE) finish()
    }

    /**
     * Splits text into the lines the card can show.
     *
     * A legacy `TextView` is one line, so a reply that is longer than the card
     * has to be cut here; the last line keeps an ellipsis when text is left
     * over, because the card cannot grow.
     */
    private fun wrap(text: String): List<String> {
        val lines = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty() && lines.size < MAX_LINES) {
            var cut = rest.length
            if (cut > LINE_CHARS) {
                val space = rest.lastIndexOf(' ')
                cut = if (space > LINE_CHARS / 3) space else LINE_CHARS
            }
            lines.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (lines.isNotEmpty() && rest.isNotEmpty()) {
            lines[lines.size - 1] = lines[lines.size - 1] + "…"
        }
        return lines
    }

    /**
     * Drops the exchange when the session ends.
     *
     * The session is created per exchange, so this is the only place its state
     * is released.
     */
    override fun onDestroy() {
        state = ""
        request = ""
        reply = ""
    }

    /**
     * Keeps the window awake while the exchange is open.
     *
     * The session is shown over whatever the device is displaying, including a
     * screen that would otherwise sleep, so the exchange has to hold the screen
     * open for as long as it is raised.
     */
    override fun onCreate() {
        setKeepAwake(true)
    }
}
