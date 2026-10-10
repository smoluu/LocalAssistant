package com.localassistant.app.service

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.view.Gravity
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

/**
 * The window is being raised and the card is still climbing from the bottom edge.
 *
 * The rise is a sequence of shows rather than an animation - see [RISE_FRAMES]
 * for why there is no animation API here - so the wake service asks for the
 * window several times in a row before the exchange itself has anything to show.
 */
const val SESSION_STATE_RISE = "rise"

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

/**
 * The card text the platform shows while it waits for the request.
 *
 * It stays within [LINE_CHARS] because a legacy `TextView` never wraps: an
 * unwrapped sentence is one line and the card measures to its widest child, so a
 * long notice stretches the whole card across the screen instead of leaving it a
 * small chat card.
 */
const val SESSION_LISTENING_TEXT = "Go ahead, I'm listening"

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
 * How big the card is.
 *
 * A session cannot read the display: `android.graphics.DisplayMetrics`,
 * `getDisplayMetrics` and `android.widget.internal` (a `Filler` spacer) are all
 * unreachable, `View.setFillDimensions` does not exist, and a forced minimum
 * (`setMinimumWidth`/`setMinimumHeight`) is not honoured by the window's measure
 * pass - forcing one left the card in the window's top-left corner, which is what
 * the user reported. The width therefore comes from padding, which is the sizing
 * API the measure pass does honour: a `ruler` child of the card carries
 * [CARD_WIDTH] as left-plus-right padding and nothing else, so it measures to
 * exactly that width, contributes no height, and pins the card to it whatever
 * the text inside it happens to be.
 *
 * [CARD_WIDTH] is a fixed pixel width rather than a fraction of the screen, and
 * that is what makes the card the same size in landscape and portrait: it is
 * narrower than the short edge of any phone-sized display, so it never has to be
 * reflowed for the orientation the device happens to be in.
 */
private const val CARD_WIDTH = 1200
private const val TEXT_SIZE = 19f
private const val CARD_SIDE_PADDING = 40
private const val CARD_TOP_PADDING = 28
private const val CARD_BOTTOM_PADDING = 28
private const val BUBBLE_PADDING = 24

/**
 * How rounded the card and the bubbles inside it are.
 *
 * A flat `setBackgroundColor(Int)` paints a sharp rectangle, which is what the
 * user saw as "the message boxes are sharp squares". The rounded route that
 * resolves here is [GradientDrawable]: it constructs with no arguments, takes
 * [GradientDrawable.setColor][setColor] and a **Float**
 * [GradientDrawable.setCornerRadius][setCornerRadius] (the Int spelling is
 * rejected with "Argument type mismatch: actual type is 'Int', but 'Float' was
 * expected"), and a `View` accepts it through `setBackgroundDrawable`/`background`
 * - the same setters that are unresolved on a [VoiceInteractionSession] itself
 * work on the widgets inside it, which is why the card can be rounded while the
 * platform's own panel behind it cannot be touched.
 */
private const val CARD_RADIUS = 28f
private const val BUBBLE_RADIUS = 20f

/**
 * The gap between the two bubbles inside the card.
 *
 * It is padding on the user bubble rather than a spacer child, because the card
 * builds its children once and the structure cannot grow between shows.
 */
private const val BUBBLE_GAP = 20

/**
 * How much text the card can show.
 *
 * A legacy `TextView` never wraps, so a reply is split into lines here and each
 * line gets one of a fixed pool of TextViews - the pool is fixed because the
 * session builds its content view once and reuses it for every later show, so
 * the structure cannot grow with the text.
 *
 * [LINE_CHARS] is chosen so a full line fits [CARD_WIDTH]: at [TEXT_SIZE] a
 * character is roughly 45 px wide, so 26 characters fill the card without
 * overflowing it.
 */
private const val MAX_LINES = 6
private const val LINE_CHARS = 26

/**
 * How the card climbs from the bottom edge, and for how many shows.
 *
 * There is no animation API here: `View.startAnimation` and `View.setAnimation`
 * exist but their argument type is `android.animation.Animation`, which this
 * toolchain can neither name nor construct - `android.animation.Animation`,
 * every `android.animatable.*` spelling and `android.app.Activity.loadAnimation`
 * are unresolved, and the only members of `android.animation` that do resolve
 * ([android.animation.Animator] and [android.animation.Keyframe]) are abstract
 * classes with no constructor. The motion is therefore a sequence of shows: the
 * wake service raises the window several times in a row and each show draws the
 * card a little higher, which reads as a slide up from the bottom edge.
 *
 * The climb is arithmetic rather than a list of heights because a file-scope
 * `const val` may only hold a primitive or a String - `const val RISE_GAP =
 * listOf(...)` is rejected with "Const 'val' has type 'List<Int>'. Only
 * primitive types and 'String' are allowed" - and the frame count is the part of
 * this contract [WakeChatService] has to see, so it has to be an Int.
 *
 * The gap is padding rather than `setMinimumHeight` because padding is the sizing
 * API the window's measure pass honours and a minimum is not. [RISE_FRAMES]
 * steps take the card from [RISE_START] down to just above [RESTING_GAP], so the
 * last step is small and the card settles above the bottom edge instead of flush
 * against it.
 */
const val RISE_FRAMES = 4
const val RISE_START = 440
const val RISE_STEP = 120
private const val RESTING_GAP = 56

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
 * [android.view.View], not a Compose surface. The card is styled to match the
 * card the app draws itself, and it is the only rendering of the wake chat - the
 * app-level overlay was removed so both halves show the same thing.
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
     * Which step of the climb from the bottom edge the next show is.
     *
     * The session is created per exchange, so this counts from the first show of
     * an exchange and never needs resetting.
     */
    private var riseFrame = 0

    /**
     * The views the window shows, built once here and only ever mutated after.
     *
     * An empty `TextView` measures nothing and a transparent background paints
     * nothing, so a state that shows no turn contributes no height and no ink.
     *
     * The riser is under the card, not above it: the stage is as tall as its
     * content and the window places that content on the screen, so the only way
     * to lift the card off the bottom edge is to make the content taller below
     * the card, and the riser is that height.
     */
    private val stage = LinearLayout(context)
    private val card = LinearLayout(context)
    private val notice = TextView(context)
    private val userBubble = LinearLayout(context)
    private val userLines = (0 until MAX_LINES).map { _ -> TextView(context) }
    private val replyBubble = LinearLayout(context)
    private val replyLines = (0 until MAX_LINES).map { _ -> TextView(context) }
    private val riser = LinearLayout(context)

    /**
     * The child that pins the card to [CARD_WIDTH], and the shapes that round it.
     *
     * The ruler is an empty child carrying [CARD_WIDTH] as left-plus-right
     * padding: it measures to that width and to no height, so the card is as
     * wide as it is whatever the text inside it happens to be - without it the
     * card measures to its widest line and jumps wider every time a longer
     * sentence is shown.
     *
     * The shapes are built once because the views are built once; [paint] only
     * ever swaps which shape a widget carries.
     */
    private val ruler = LinearLayout(context)
    private val cardShape = rounded(SURFACE, CARD_RADIUS)
    private val clearShape = rounded(TRANSPARENT, CARD_RADIUS)
    private val userShape = rounded(USER_BUBBLE, BUBBLE_RADIUS)
    private val replyShape = rounded(REPLY_BUBBLE, BUBBLE_RADIUS)

    /**
     * Records what the platform is about to show.
     *
     * AOSP calls this before it asks for the content view, so the text has to be
     * taken here rather than read where the views are built. A rise frame only
     * advances the climb; every other state settles the card at its resting
     * height, so an exchange that is interrupted mid-climb still ends in the
     * same place.
     */
    override fun onPrepareShow(args: android.os.Bundle, showFlags: Int) {
        state = args.getString(SESSION_STATE_KEY) ?: ""
        request = args.getString(SESSION_REQUEST_KEY) ?: ""
        reply = args.getString(SESSION_REPLY_KEY) ?: ""
        if (state == SESSION_STATE_RISE) {
            if (riseFrame < RISE_FRAMES) riseFrame++
        } else {
            riseFrame = RISE_FRAMES
        }
        paint()
    }

    /**
     * Wires the card once and returns the stage the window shows.
     *
     * The structure is wired here and never again; only [paint] changes what is
     * drawn. Painting again after wiring is what makes the first exchange show
     * the text that [onPrepareShow] already recorded, and it is harmless on
     * every later show because painting is idempotent.
     *
     * The stage is deliberately left unpainted: it is as large as the card plus
     * the riser, so a background here would paint the riser too and turn the
     * card into a tall block. Only the card and the bubbles inside it are painted.
     */
    override fun onCreateContentView(): View {
        Log.i(
            "WakeChatSessionService",
            "Wiring the card for state=$state, a ${request.length}-character request and a ${reply.length}-character reply"
        )
        stage.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        stage.addView(card)
        stage.addView(riser)
        card.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        card.setPadding(CARD_SIDE_PADDING, CARD_TOP_PADDING, CARD_SIDE_PADDING, CARD_BOTTOM_PADDING)
        card.addView(ruler)
        ruler.setPadding(CARD_WIDTH / 2, 0, CARD_WIDTH / 2, 0)
        card.addView(notice)
        notice.setGravity(Gravity.CENTER)
        notice.textSize = TEXT_SIZE
        notice.setTextColor(MUTED)
        card.addView(userBubble)
        userBubble.setGravity(Gravity.CENTER)
        userBubble.setPadding(BUBBLE_PADDING, BUBBLE_PADDING, BUBBLE_PADDING, BUBBLE_PADDING + BUBBLE_GAP)
        userLines.forEach { line ->
            userBubble.addView(line)
            line.setGravity(Gravity.CENTER)
            line.textSize = TEXT_SIZE
            line.setTextColor(MUTED)
        }
        card.addView(replyBubble)
        replyBubble.setGravity(Gravity.CENTER)
        replyBubble.setPadding(BUBBLE_PADDING, BUBBLE_PADDING, BUBBLE_PADDING, BUBBLE_PADDING)
        replyLines.forEach { line ->
            replyBubble.addView(line)
            line.setGravity(Gravity.CENTER)
            line.textSize = TEXT_SIZE
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
        card.background = if (state == SESSION_STATE_CLOSE) clearShape else cardShape
        userBubble.background = if (request.isBlank()) clearShape else userShape
        replyBubble.background = if (reply.isBlank()) clearShape else replyShape
        if (state == SESSION_STATE_LISTENING) notice.text = SESSION_LISTENING_TEXT
        wrap(request).forEachIndexed { index, line ->
            if (index < userLines.size) userLines[index].text = line
        }
        wrap(reply).forEachIndexed { index, line ->
            if (index < replyLines.size) replyLines[index].text = line
        }
        riser.setPadding(0, gap(), 0, 0)
        riser.setBackgroundColor(TRANSPARENT)
    }

    /**
     * How far the card sits above the bottom edge for the current show.
     *
     * A closed exchange has no card at all, so it gets no gap either - the riser
     * must not hold the window open above nothing.
     */
    private fun gap(): Int {
        if (state == SESSION_STATE_CLOSE) return 0
        if (riseFrame >= RISE_FRAMES) return RESTING_GAP
        return RISE_START - RISE_STEP * riseFrame
    }

    /**
     * A filled, rounded rectangle - the shape of the card and of a bubble.
     *
     * This is the only rounding available to a session: a widget's
     * `setBackgroundColor(Int)` paints a sharp rectangle, while
     * [GradientDrawable] is a concrete drawable that constructs with no arguments
     * and takes a solid [GradientDrawable.setColor][setColor] plus a Float
     * [GradientDrawable.setCornerRadius][setCornerRadius]. A single colour with
     * no colour array set fills solid rather than gradient, and a fully
     * transparent colour paints nothing, which is how a turn that has no text
     * yet contributes no ink.
     */
    private fun rounded(color: Int, radius: Float): GradientDrawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        shape.setCornerRadius(radius)
        return shape
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
        riseFrame = 0
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
