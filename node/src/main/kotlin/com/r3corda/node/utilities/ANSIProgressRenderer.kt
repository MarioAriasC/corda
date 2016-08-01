package com.r3corda.node.utilities

import com.r3corda.core.utilities.Emoji
import com.r3corda.core.utilities.ProgressTracker
import com.r3corda.node.utilities.ANSIProgressRenderer.progressTracker
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractOutputStreamAppender
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.appender.OutputStreamManager
import org.apache.logging.log4j.core.config.LoggerConfig
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import org.fusesource.jansi.AnsiOutputStream
import rx.Subscription

/**
 * Knows how to render a [ProgressTracker] to the terminal using coloured, emoji-fied output. Useful when writing small
 * command line tools, demos, tests etc. Just set the [progressTracker] field and it will go ahead and start drawing
 * if the terminal supports it. Otherwise it just prints out the name of the step whenever it changes.
 *
 * When a progress tracker is on the screen, it takes over the bottom part and reconfigures logging so that, assuming
 * 1 log event == 1 line, the progress tracker is always glued to the bottom and logging scrolls above it.
 *
 * TODO: More thread safety
 */
object ANSIProgressRenderer {
    private var installedYet = false
    private var subscription: Subscription? = null

    private var usingANSI = false

    var progressTracker: ProgressTracker? = null
        set(value) {
            subscription?.unsubscribe()

            field = value
            if (!installedYet) {
                setup()
            }

            // Reset the state when a new tracker is wired up.
            prevMessagePrinted = null
            prevLinesDrawn = 0
            draw(true)
            subscription = value?.changes?.subscribe { draw(true) }
        }

    private fun setup() {
        AnsiConsole.systemInstall()

        // This line looks weird as hell because the magic code to decide if we really have a TTY or not isn't
        // actually exposed anywhere as a function (weak sauce). So we have to rely on our knowledge of jansi
        // implementation details.
        usingANSI = AnsiConsole.wrapOutputStream(System.out) !is AnsiOutputStream

        if (usingANSI) {
            // This super ugly code hacks into log4j and swaps out its console appender for our own. It's a bit simpler
            // than doing things the official way with a dedicated plugin, etc, as it avoids mucking around with all
            // the config XML and lifecycle goop.
            val manager = LogManager.getContext(false) as LoggerContext
            val consoleAppender = manager.configuration.appenders.values.filterIsInstance<ConsoleAppender>().single()
            val scrollingAppender = object : AbstractOutputStreamAppender<OutputStreamManager>(
                    consoleAppender.name, consoleAppender.layout, consoleAppender.filter,
                    consoleAppender.ignoreExceptions(), true, consoleAppender.manager) {
                override fun append(event: LogEvent) {
                    // We lock on the renderer to avoid threads that are logging to the screen simultaneously messing
                    // things up. Of course this slows stuff down a bit, but only whilst this little utility is in use.
                    // Eventually it will be replaced with a real GUI and we can delete all this.
                    synchronized(ANSIProgressRenderer) {
                        if (progressTracker != null) {
                            val ansi = Ansi.ansi()
                            repeat(prevLinesDrawn) { ansi.eraseLine().cursorUp(1).eraseLine() }
                            System.out.print(ansi)
                            System.out.flush()
                        }

                        super.append(event)

                        if (progressTracker != null)
                            draw(false)
                    }
                }
            }
            scrollingAppender.start()
            manager.configuration.appenders[consoleAppender.name] = scrollingAppender
            val loggerConfig: LoggerConfig = manager.configuration.loggers.values.single()
            val appenderRefs = loggerConfig.appenderRefs
            loggerConfig.appenders.keys.forEach { loggerConfig.removeAppender(it) }
            appenderRefs.forEach { loggerConfig.addAppender(manager.configuration.appenders[it.ref], it.level, it.filter) }
            manager.updateLoggers()
        }

        installedYet = true
    }

    // prevMessagePrinted is just for non-ANSI mode.
    private var prevMessagePrinted: String? = null
    // prevLinesDraw is just for ANSI mode.
    private var prevLinesDrawn = 0

    @Synchronized private fun draw(moveUp: Boolean) {
        val pt = progressTracker!!

        if (!usingANSI) {
            val currentMessage = pt.currentStepRecursive.label
            if (currentMessage != prevMessagePrinted) {
                println(currentMessage)
                prevMessagePrinted = currentMessage
            }
            return
        }

        // Handle the case where the number of steps in a progress tracker is changed during execution.
        val ansi = Ansi.ansi()
        if (prevLinesDrawn > 0 && moveUp)
            ansi.cursorUp(prevLinesDrawn)

        // Put a blank line between any logging and us.
        ansi.eraseLine()
        ansi.newline()
        val newLinesDrawn = 1 + pt.renderLevel(ansi, 0, pt.allSteps)
        if (newLinesDrawn < prevLinesDrawn) {
            // If some steps were removed from the progress tracker, we don't want to leave junk hanging around below.
            val linesToClear = prevLinesDrawn - newLinesDrawn
            repeat(linesToClear) {
                ansi.eraseLine()
                ansi.newline()
            }
            ansi.cursorUp(linesToClear)
        }
        prevLinesDrawn = newLinesDrawn

        // Need to force a flush here in order to ensure stderr/stdout sync up properly.
        System.out.print(ansi)
        System.out.flush()
    }

    // Returns number of lines rendered.
    private fun ProgressTracker.renderLevel(ansi: Ansi, indent: Int, allSteps: List<Pair<Int, ProgressTracker.Step>>): Int {
        with(ansi) {
            var lines = 0
            for ((index, step) in steps.withIndex()) {
                // Don't bother rendering these special steps in some cases.
                if (step == ProgressTracker.UNSTARTED) continue
                if (indent > 0 && step == ProgressTracker.DONE) continue

                val marker = when {
                    index < stepIndex -> Emoji.CODE_GREEN_TICK + "  "
                    index == stepIndex && step == ProgressTracker.DONE -> Emoji.CODE_GREEN_TICK + "  "
                    index == stepIndex -> Emoji.CODE_RIGHT_ARROW + "  "
                    else -> "   "
                }
                a("    ".repeat(indent))
                a(marker)

                val active = index == stepIndex && step != ProgressTracker.DONE
                if (active) bold()
                a(step.label)
                if (active) boldOff()

                eraseLine(Ansi.Erase.FORWARD)
                newline()
                lines++

                val child = getChildProgressTracker(step)
                if (child != null)
                    lines += child.renderLevel(ansi, indent + 1, allSteps)
            }
            return lines
        }
    }
}