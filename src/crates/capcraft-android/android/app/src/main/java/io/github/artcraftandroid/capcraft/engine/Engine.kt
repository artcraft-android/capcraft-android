package io.github.artcraftandroid.capcraft.engine

import android.graphics.Bitmap
import io.github.artcraftandroid.capcraft.core.MobileException
import io.github.artcraftandroid.capcraft.core.Picture
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Native engine calls run FFmpeg, wgpu/Vulkan shader compilation (Adreno `libllvm-qgl.so`), and ONNX
 * inference across JNI/JNA. Android's default JVM worker threads only have ~1 MiB stacks, which can
 * overflow inside GPU shader compilers or deep media filtergraphs; give every engine pool an 8 MiB stack.
 */
private fun nativePool(name: String, threads: Int): CoroutineDispatcher {
    val index = AtomicInteger(0)
    return Executors.newFixedThreadPool(threads) { runnable ->
        Thread(null, runnable, "$name-${index.incrementAndGet()}", 8L * 1024L * 1024L).apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
}

/** The threads engine calls run on. Never call the engine from the main thread. */
object Threads {
    /** Edits run one at a time, in the order the UI issued them. */
    val commands: CoroutineDispatcher = nativePool("capcraft-cmd", 1)

    /** The preview: one picture at a time, the newest wins. */
    val preview: CoroutineDispatcher = nativePool("capcraft-preview", 1)

    /** Timeline filmstrips, waveforms and covers. */
    val thumbnails: CoroutineDispatcher = nativePool("capcraft-thumbs", 2)
}

/** A message for the user from anything the engine (or the system) threw. */
val Throwable.userMessage: String
    get() = when (this) {
        is MobileException -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
        else -> message?.takeIf { it.isNotBlank() && !it.contains("Exception") } ?: "Something went wrong."
    }

/** A picture's pixels as a new bitmap. */
fun Picture.toBitmap(): Bitmap? = intoBitmap(null)

/** A picture's pixels into [into] when it has the same size (no new allocation), else a new bitmap. */
fun Picture.intoBitmap(into: Bitmap?): Bitmap? {
    val w = width.toInt()
    val h = height.toInt()
    if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
    val bytes = w.toLong() * h.toLong() * 4L
    if (bytes <= 0L || bytes > Int.MAX_VALUE || rgba.size < bytes.toInt()) return null
    val b = if (into != null && !into.isRecycled && into.isMutable && into.width == w && into.height == h) into
    else runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
    // ARGB_8888 keeps its bytes in memory as R, G, B, A: the engine's order
    b.copyPixelsFromBuffer(ByteBuffer.wrap(rgba, 0, bytes.toInt()))
    return b
}

/** Microseconds as the editor's clock shows them: 00:07, or 1:02:03 past an hour. */
fun clock(us: Long): String {
    val total = (us.coerceAtLeast(0) / 1_000_000)
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Seconds with one decimal, as clip labels show durations: 3.2s. */
fun seconds1(us: Long): String = "%.1fs".format(us / 1_000_000.0)
