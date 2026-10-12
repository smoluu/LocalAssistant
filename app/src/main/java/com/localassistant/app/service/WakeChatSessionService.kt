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
 * The reply is arriving from the LLM as it is written, so the answer bubble
 * grows with the text that has arrived so far.
 */
const val SESSION_STATE_STREAMING = "streaming"

/** The reply is being spoken by the TTS endpoint. */
const val SESSION_STATE_SPEAKING = "speaking"

/**
 * The exchange has been closed, so the session is asked to show nothing and to
 * tear its window down.
 */
const val SESSION_STATE_CLOSE = "close"

/**
 * The card colours, matching the app's Gruvbox dark scheme. A session is drawn
 * by the system, so it cannot read [Theme] and has to name them itself.
 *
 * The surface is translucent rather than solid: the window is transparent and
 * the platform paints nothing behind the card, so the screen the device is
 * showing reads through it and the exchange stays a floating chat card instead
 * of a panel over the display.
 */
private const val TRANSPARENT = 0x00000000

private const val SURFACE = 0xE6141414.toInt()

private const val MUTED = 0xFFAAAAAA.toInt()

private const val USER_TEXT = 0xFFE8E8E8.toInt()

private const val REPLY_TEXT = 0xFFF2A94C.toInt()

private const val USER_BUBBLE = 0xFF3A3A3A.toInt()

private const val REPLY_BUBBLE = 0xFF342A1F.toInt()

/**
 * The colour of the dot beside the status line, which is the exchange's phase
 * wearing the colour it is doing in.
 *
 * The dot is what makes the status line readable at a glance across a room: the
 * label needs the eye to be on the card, the colour does not. A state the wake
 * service does not name is taken as a fault and wears [ERROR].
 */
private const val DOT_LISTENING = 0xFF67B744.toInt()

private const val DOT_THINKING = 0xFFEF7E3B.toInt()

private const val DOT_ANSWERING = 0xFFF2A94C.toInt()

private const val ERROR = 0xFFCC3D2B.toInt()

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
 * measured against the **short** edge of the display, so the same card still fits
 * when the device turns to portrait - a width taken from the long edge would be
 * wider than the screen there and the sides of the card would be cut off.
 */
private const val CARD_WIDTH = 1560
private const val TEXT_SIZE = 22f
private const val STATUS_SIZE = 17f
private const val CARD_SIDE_PADDING = 44
private const val CARD_TOP_PADDING = 28
private const val CARD_BOTTOM_PADDING = 28
private const val BUBBLE_PADDING = 26

/**
 * How tall a line of text and the status line actually are, which is what the
 * card's height is built from.
 *
 * A legacy [android.widget.TextView] reports no intrinsic size, so the session
 * cannot ask the views how tall the text they hold is - the only heights it
 * knows are the ones it gives them. Measured on the tab, the view draws at about
 * **twice** the pixel size it is handed: at a 44 px setting the glyphs came out
 * ~88 px tall, a character advanced ~43 px, and the line box measured 125 px.
 * That is why the sizes above are half what the card should read as - a 22 px
 * setting is a ~44 px card font - and these are the resulting line boxes, about
 * 2.8 times the setting rather than the 1.2 times Roboto's own ascent, descent
 * and leading suggest, because the view adds its internal padding to the font
 * box. Underestimate these and the last line of a reply is cut off at the bottom
 * edge of the card, which is exactly what the earlier build showed.
 */
private const val LINE_HEIGHT = 62
private const val STATUS_HEIGHT = 48

/**
 * The height the card rests at, whatever is inside it.
 *
 * Without this the card is exactly as tall as its content, which is nothing at
 * the start of an exchange: the card measured 0, then 68, then 0 across the rise
 * shows, so the panel jumped instead of climbing, and a two-word request sat in a
 * sliver far too short to read as a chat card. The window is cut to at least this
 * much card, so the card is [CARD_HEIGHT] until the text genuinely needs more,
 * and only then does it grow.
 */
private const val CARD_HEIGHT = 620

/**
 * The tallest the card may become when an answer genuinely needs it.
 *
 * A long reply streams into the card line by line, and without a ceiling a
 * rambling answer turns the card back into a panel over the whole display. This
 * is set to the height [neededHeight] asks for at the fullest exchange the card
 * is allowed to show, so the last line of a reply is never cut off by the ceiling
 * itself - only by [REPLY_MAX_LINES].
 */
private const val MAX_CARD_HEIGHT = 900

/**
 * How much of the window the platform keeps for itself around the card.
 *
 * Measured on the tab: whatever height the window is cut to, the card inside it
 * measures exactly that less this. The card is stretched to fill the window, so
 * this is the only room the card cannot claim, and it is why the window - not the
 * card - decides how tall the card is.
 */
private const val WINDOW_OVERHEAD = 96

/**
 * How far the card floats above the bottom edge of the display.
 *
 * It is zero, and the card sits flush against the bottom edge: the window is
 * pinned there and the card is stretched to fill it, so the only way to leave a
 * margin under the card was the padding-on-an-empty-View trick - and measured, a
 * bare [android.view.View] carrying top padding reports **0** height, so the
 * trick does nothing here. The card climbing straight out of the bottom edge is
 * what the rise reads as anyway.
 */
private const val BOTTOM_MARGIN = 0

/**
 * The width the card's children may use, which is what a line of text and a
 * bubble are both measured against.
 *
 * A bubble that only spans its own longest line makes the card jump wider every
 * time a longer sentence arrives, so each bubble carries a ruler of this width
 * and the card is the same shape for the whole exchange.
 */
private const val INNER_WIDTH = CARD_WIDTH - CARD_SIDE_PADDING * 2

/**
 * The width a bubble's text may use, which is what a line is wrapped to.
 *
 * A bubble spans the card's inner width so the card never jumps wider as longer
 * sentences arrive, and its text is inset by the bubble's own padding - so the
 * text budget is that width less the padding, not the card width.
 */
private const val BUBBLE_TEXT_WIDTH = INNER_WIDTH - BUBBLE_PADDING * 2

/**
 * The size of the window the system raises for the exchange.
 *
 * The window used to be left exactly as the platform builds it, which is a
 * `fillxfill` surface covering the whole display: the platform paints its own
 * rounded panel behind whatever a session draws, so the exchange looked like a
 * large white box over the screen and no amount of styling the card could change
 * that. Neither half of that is fixed, though: [VoiceInteractionSession.getWindow][window]
 * hands back the session's [android.app.Dialog], and that Dialog's
 * [android.view.Window] answers `setLayout` **and** `setBackgroundDrawable`, so
 * the window can be cut to the card *and* the panel it carries can be cleared -
 * handing it a transparent shape removes the cream panel entirely, which is what
 * lets the screen underneath read through the card.
 *
 * The height is worked out from the text about to be drawn, never read back off
 * the card, and [MAX_WINDOW_HEIGHT] is the ceiling that keeps a long answer from
 * turning the card back into a panel over the display.
 */
private const val CARD_OUTER_WIDTH = CARD_WIDTH + CARD_SIDE_PADDING * 2
private const val MAX_WINDOW_HEIGHT = MAX_CARD_HEIGHT + WINDOW_OVERHEAD + BOTTOM_MARGIN

/**
 * The glyph that leads the status line, wearing the colour of the phase.
 *
 * It is a character rather than a drawn shape because a session has no drawable
 * to place: the only paint a widget can carry is a background, and a background
 * fills the whole widget. Giving it its own `TextView` in the status row is what
 * lets the dot wear the phase's colour while the label beside it stays muted -
 * one `TextView` can hold two colours, and the phase would lose its colour the
 * moment the label shared it.
 */
private const val PHASE_DOT = "\u2B24"

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
 * The gap between the status dot and the label beside it.
 *
 * A row lays its children flush against each other, so the breathing room the
 * card's own padding would give is nothing here - the gap has to be padding on
 * the dot itself, which is also what keeps the dot's ink clear of the label's.
 */
private const val STATUS_DOT_GAP = 14

/**
 * The main-axis orientation every container in the card is forced to.
 *
 * The one-argument [android.widget.LinearLayout] constructor does **not** stack
 * children here: measured on the tab, every child of the panel reported the
 * same height - the panel's inner height - and the card's two children each
 * measured the full card height, which is cross-axis stretching, i.e. a
 * horizontal layout. The card therefore painted as one flat band with the
 * bubbles side by side and clipped, and no amount of padding or gravity changed
 * it. AOSP names the orientations as the statics `HORIZONTAL = 0` and
 * `VERTICAL = 1`; Kotlin cannot reach a static member of a Java class
 * (`android.widget.LinearLayout.VERTICAL` is rejected, as is the
 * `android.widget.LinearLayoutHorizontal` spelling), so the value is named here
 * and applied with [android.widget.LinearLayout.setOrientation][setOrientation].
 */
private const val VERTICAL = 1

/**
 * How much text the card can show.
 *
 * A legacy `TextView` never wraps, so a reply is split into lines here and each
 * line gets one of a fixed pool of TextViews - the pool is fixed because the
 * session builds its content view once and reuses it for every later show, so
 * the structure cannot grow with the text.
 *
 * [LINE_CHARS] is chosen so a full line fits [BUBBLE_TEXT_WIDTH]: at [TEXT_SIZE] a
 * character is roughly 22 px wide, so 64 characters fill a bubble without
 * overflowing it. The counts are deliberately small - a request is a spoken
 * sentence and an answer is read aloud - because the card must stay a card:
 * [USER_MAX_LINES] plus [REPLY_MAX_LINES] lines is the tallest it is ever allowed
 * to be, and [MAX_WINDOW_HEIGHT] is the ceiling the window is cut to.
 */
private const val USER_MAX_LINES = 2
private const val REPLY_MAX_LINES = 8
private const val LINE_CHARS = 64

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
 * The climb runs **up** from the bottom edge to [CARD_HEIGHT] across [RISE_FRAMES]
 * shows, so the first show puts the card flush against the bottom edge - it pops
 * up from there, rather than appearing already in mid-air - and the last settles
 * it at its resting height.
 *
 * The climb eases out rather than stepping evenly: each show lifts the card by a
 * smaller share of the remaining distance, so it leaves the edge briskly and
 * settles softly, which is what a pop-up reads as. The easing is arithmetic
 * because a file-scope `const val` may only hold a primitive or a String -
 * `const val RISE_GAP = listOf(...)` is rejected with "Const 'val' has type
 * 'List<Int>'. Only primitive types and 'String' are allowed" - and the frame
 * count is the part of this contract [WakeChatService] has to see, so it has to
 * be an Int.
 */
const val RISE_FRAMES = 6

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
     * What the next show must draw, wrapped once so the window and the paint
     * agree on how many lines there are.
     */
    private var userShown: List<String> = listOf()
    private var replyShown: List<String> = listOf()

    /**
     * The views the window shows, built once here and only ever mutated after.
     *
     * An empty `TextView` measures nothing and a transparent background paints
     * nothing, so a state that shows no turn contributes no height and no ink.
     *
     * The stage holds only the card: the window is pinned to the bottom edge and
     * stretches the card to fill itself, so the card's height is the window's
     * doing - see [sizeWindow] - and nothing has to be stacked under it.
     */
    private val stage = LinearLayout(context)
    private val card = LinearLayout(context)
    private val panel = LinearLayout(context)
    private val statusLine = TextView(context)
    private val statusDot = TextView(context)
    private val userBubble = LinearLayout(context)
    private val userLines = (0 until USER_MAX_LINES).map { _ -> TextView(context) }
    private val replyBubble = LinearLayout(context)
    private val replyLines = (0 until REPLY_MAX_LINES).map { _ -> TextView(context) }

    /**
     * The children that pin the card to a fixed size, the rows the bubbles sit
     * in, and the shapes that round them.
     *
     * The ruler is an empty child carrying the card's width as left-plus-right
     * padding: it measures to that width and to no height, so the card stays the
     * same shape for the whole exchange instead of jumping wider whenever a
     * longer sentence arrives.
     *
     * Each bubble sits inside its own row rather than directly in the panel. The
     * panel is a vertical container, so it stretches its children across the full
     * inner width - measured on the tab, the user bubble came out at 1560 px
     * regardless of how much text it held, which read as a band across the card
     * rather than a bubble. A row is the default horizontal container, and there
     * the bubble keeps its **own** measured width along the main axis, so it hugs
     * its longest line; the row itself is still stretched by the panel, which is
     * what leaves room for gravity to push the reply to the right edge.
     *
     * The shapes are built once because the views are built once; [paint] only
     * ever swaps which shape a widget carries.
     */
    private val ruler = LinearLayout(context)
    private val statusRow = LinearLayout(context)
    private val userRow = LinearLayout(context)
    private val replyRow = LinearLayout(context)
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
        userShown = wrap(request, USER_MAX_LINES)
        replyShown = wrap(reply, REPLY_MAX_LINES)
        if (state == SESSION_STATE_RISE) {
            if (riseFrame < RISE_FRAMES) riseFrame++
        } else {
            riseFrame = RISE_FRAMES
        }
        sizeWindow()
        paint()
    }

    /**
     * Cuts the window the system raises for the exchange down to the card.
     *
     * The platform builds the session window as a `fillxfill` surface and lays it
     * out to fill the display, then paints its own rounded panel across the whole
     * of it - that panel is why the exchange used to cover the screen as a large
     * white box that no amount of card styling could shrink. The window is
     * reachable from here, though: [window] is the session's [android.app.Dialog]
     * and that Dialog's [android.view.Window] answers `setLayout`, so the window
     * can be sized to the card and pinned to the bottom edge. The panel then
     * measures to the card, and everything outside it is the app underneath -
     * which is what makes the exchange a small card floating over everything
     * rather than a panel over the whole display.
     *
     * AOSP only lays the window out to fill the screen while it is *not* visible
     * (`doShow` calls `ensureWindowAdded` inside `if (!mWindowVisible)`), and it
     * calls [onPrepareShow] before that, so sizing here wins on the first show
     * and is never fought back on the ones that follow.
     *
     * The height is worked out from the text that is about to be drawn rather
     * than read back off the card, because the window's fixed height stretches
     * the card back to fill it: deriving the one from the measured other is a
     * feedback loop, and it is what made the card grow by 54 px on every show
     * forever. [neededHeight] is the truth, [CARD_HEIGHT] is the floor it rests
     * at, and [MAX_WINDOW_HEIGHT] is the ceiling a long answer may not pass.
     */
    private fun sizeWindow() {
        val dialog = window
        if (dialog == null) {
            Log.i("WakeChatSessionService", "No window yet, so nothing to cut")
            return
        }
        val surface = dialog.window
        if (surface == null) {
            Log.i("WakeChatSessionService", "The dialog carries no window to cut")
            return
        }
        val needed = if (state == SESSION_STATE_CLOSE) 0 else neededHeight(userShown.size, replyShown.size)
        val risen = riseHeight()
        val wanted = if (needed > risen) needed else risen
        val tall = if (wanted > MAX_CARD_HEIGHT) MAX_CARD_HEIGHT else wanted
        val cut = tall + WINDOW_OVERHEAD + BOTTOM_MARGIN
        val height = if (cut > MAX_WINDOW_HEIGHT) MAX_WINDOW_HEIGHT else cut
        surface.setLayout(CARD_OUTER_WIDTH, height)
        surface.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        surface.setBackgroundDrawable(clearShape)
        Log.i(
            "WakeChatSessionService",
            "Cut the window to ${CARD_OUTER_WIDTH} by ${height} for a ${tall}-tall card at frame ${riseFrame}"
        )
    }

    /**
     * Wires the card once and returns the stage the window shows.
     *
     * The structure is wired here and never again; only [paint] changes what is
     * drawn. Painting again after wiring is what makes the first exchange show
     * the text that [onPrepareShow] already recorded, and it is harmless on
     * every later show because painting is idempotent.
     *
     * The stage is deliberately left unpainted: it is as large as the card, so
     * a background here would paint the window's margin too and turn the card
     * into a block that reaches the bottom edge. Only the card and the bubbles
     * inside it are painted.
     */
    override fun onCreateContentView(): View {
        Log.i(
            "WakeChatSessionService",
            "Wiring the card for state=$state, a ${request.length}-character request and a ${reply.length}-character reply"
        )
        stage.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        stage.setOrientation(VERTICAL)
        stage.addView(card)
        card.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        card.setOrientation(VERTICAL)
        card.addView(panel)
        panel.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        panel.setOrientation(VERTICAL)
        panel.setPadding(CARD_SIDE_PADDING, CARD_TOP_PADDING, CARD_SIDE_PADDING, CARD_BOTTOM_PADDING)
        panel.addView(ruler)
        ruler.setPadding(CARD_WIDTH / 2, 0, CARD_WIDTH / 2, 0)
        panel.addView(statusRow)
        statusRow.setGravity(Gravity.LEFT)
        statusRow.setPadding(0, 0, 0, BUBBLE_GAP)
        statusRow.addView(statusDot)
        statusDot.textSize = STATUS_SIZE
        statusDot.setPadding(0, 0, STATUS_DOT_GAP, 0)
        statusRow.addView(statusLine)
        statusLine.textSize = STATUS_SIZE
        panel.addView(userRow)
        userRow.setGravity(Gravity.TOP or Gravity.LEFT)
        userRow.addView(userBubble)
        userBubble.setOrientation(VERTICAL)
        userBubble.setPadding(BUBBLE_PADDING, BUBBLE_PADDING, BUBBLE_PADDING, BUBBLE_PADDING + BUBBLE_GAP)
        userLines.forEach { line ->
            userBubble.addView(line)
            line.setGravity(Gravity.LEFT)
            line.textSize = TEXT_SIZE
            line.setTextColor(USER_TEXT)
        }
        panel.addView(replyRow)
        replyRow.setGravity(Gravity.TOP or Gravity.RIGHT)
        replyRow.addView(replyBubble)
        replyBubble.setOrientation(VERTICAL)
        replyBubble.setPadding(BUBBLE_PADDING, BUBBLE_PADDING, BUBBLE_PADDING, BUBBLE_PADDING)
        replyLines.forEach { line ->
            replyBubble.addView(line)
            line.setGravity(Gravity.RIGHT)
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
     * which is how a closed exchange looks like nothing at all.
     *
     * The status line is the one thing painted in every open state, so the card
     * always says what it is doing even before a word has been heard.
     */
    private fun paint() {
        statusDot.text = ""
        statusLine.text = ""
        userLines.forEach { line -> line.text = "" }
        replyLines.forEach { line -> line.text = "" }
        val closed = state == SESSION_STATE_CLOSE
        panel.background = if (closed) clearShape else cardShape
        statusDot.text = if (closed) "" else PHASE_DOT
        statusDot.setTextColor(if (closed) TRANSPARENT else phaseColor())
        statusLine.text = if (closed) "" else statusLabel()
        statusLine.setTextColor(if (closed) TRANSPARENT else MUTED)
        userBubble.background = if (request.isBlank()) clearShape else userShape
        replyBubble.background = if (reply.isBlank()) clearShape else replyShape
        val userPad = if (request.isBlank()) 0 else BUBBLE_PADDING
        userBubble.setPadding(userPad, userPad, userPad, userPad + if (request.isBlank()) 0 else BUBBLE_GAP)
        val replyPad = if (reply.isBlank()) 0 else BUBBLE_PADDING
        replyBubble.setPadding(replyPad, replyPad, replyPad, replyPad)
        userShown.forEachIndexed { index, line ->
            if (index < userLines.size) userLines[index].text = line
        }
        replyShown.forEachIndexed { index, line ->
            if (index < replyLines.size) replyLines[index].text = line
        }
    }

    /**
     * How tall the card has to be to hold the lines it is showing, padding
     * included and nothing clamped away.
     *
     * This is the truth [sizeWindow] cuts the window to: the card is stretched to
     * fill the window, so the only way to give it room for a long answer is to
     * give the window that room. The heights are added up rather than measured
     * because a legacy [android.widget.TextView] reports no intrinsic size, so
     * [LINE_HEIGHT] and [STATUS_HEIGHT] are the only line boxes this can see.
     */
    private fun neededHeight(userLineCount: Int, replyLineCount: Int): Int {
        val status = STATUS_HEIGHT + BUBBLE_GAP
        val user =
            if (userLineCount == 0) 0
            else BUBBLE_PADDING * 2 + userLineCount * LINE_HEIGHT + BUBBLE_GAP
        val answer =
            if (replyLineCount == 0) 0
            else BUBBLE_PADDING * 2 + replyLineCount * LINE_HEIGHT
        return CARD_TOP_PADDING + CARD_BOTTOM_PADDING + status + user + answer
    }

    /**
     * The small line beside the status dot that says what the exchange is doing.
     *
     * A session only ever sees the state the wake service put in the bundle, so
     * the label is derived here rather than sent: the same show that grows the
     * answer bubble is the one that says the answer is still arriving. A settled
     * reply still carries a label, because the card is open and the user should
     * be able to tell it is open.
     */
    private fun statusLabel(): String = when (state) {
        SESSION_STATE_RISE -> "Ready"
        SESSION_STATE_LISTENING -> "Listening…"
        SESSION_STATE_REQUEST -> "Thinking…"
        SESSION_STATE_STREAMING -> "Answering…"
        SESSION_STATE_SPEAKING -> "Speaking…"
        SESSION_STATE_REPLY -> "Here you go"
        else -> "Something went wrong"
    }

    /**
     * The colour of the status dot, which is the exchange's phase wearing the
     * colour it is doing in.
     *
     * Only the dot wears it: the label beside it stays [MUTED], so the phase is
     * legible from across a room by colour alone while the words stay quiet
     * instead of shouting the state. A state the wake service does not name is
     * taken as a fault and wears [ERROR].
     */
    private fun phaseColor(): Int = when (state) {
        SESSION_STATE_LISTENING -> DOT_LISTENING
        SESSION_STATE_REQUEST -> DOT_THINKING
        SESSION_STATE_STREAMING, SESSION_STATE_SPEAKING, SESSION_STATE_REPLY -> DOT_ANSWERING
        else -> ERROR
    }

    /**
     * How tall the card is for the current show, which is how it climbs.
     *
     * The card fills the window it is given, so the climb is the window growing
     * upward from the bottom edge: the first show is a stub against the edge and
     * the last settles the card at [CARD_HEIGHT]. The climb eases out, so each
     * show adds a smaller share of what is left and the card leaves the edge
     * briskly and settles softly, which is what a pop-up reads as. A closed
     * exchange has no card at all, so it is cut to nothing.
     */
    private fun riseHeight(): Int {
        if (state == SESSION_STATE_CLOSE) return 0
        if (riseFrame >= RISE_FRAMES) return CARD_HEIGHT
        val remaining = 1f - (riseFrame - 1) / (RISE_FRAMES - 1f)
        return (CARD_HEIGHT * (1f - remaining * remaining)).toInt()
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
        Log.i(
            "WakeChatSessionService",
            "Shown state=$state stageW=${stage.width} stageH=${stage.height} cardW=${card.width} cardH=${card.height} panelW=${panel.width} panelH=${panel.height} rowW=${statusRow.width} dotW=${statusDot.width} statusW=${statusLine.width} statusH=${statusLine.height} u0W=${userLines[0].width} u0H=${userLines[0].height} u0T=${userLines[0].text.length} r0T=${replyLines[0].text.length} userBW=${userBubble.width} userBH=${userBubble.height} replyBW=${replyBubble.width} replyBH=${replyBubble.height} userRowW=${userRow.width} replyRowW=${replyRow.width}"
        )
        if (state == SESSION_STATE_CLOSE) finish()
    }

    /**
     * Splits text into the lines the card can show.
     *
     * A legacy `TextView` is one line, so a reply longer than the card has to be
     * cut here. The cut is searched only inside the next [LINE_CHARS] characters:
     * searching the whole remainder instead makes the break land far past the
     * card edge, which is what painted lines wider than the card and spilling
     * over the bubbles. When the text is still not finished at the last slot, the
     * ellipsis is taken *inside* the budget rather than appended to it, so a
     * wrapped line can never be wider than the line the card can hold.
     */
    private fun wrap(text: String, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty() && lines.size < maxLines) {
            val last = lines.size == maxLines - 1
            var cut = rest.length
            if (cut > LINE_CHARS) {
                val room = if (last) LINE_CHARS - 1 else LINE_CHARS
                val space = rest.substring(0, room).lastIndexOf(' ')
                cut = if (space > room / 3) space else room
            }
            var line = rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
            if (last && rest.isNotEmpty()) line += "…"
            lines.add(line)
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
