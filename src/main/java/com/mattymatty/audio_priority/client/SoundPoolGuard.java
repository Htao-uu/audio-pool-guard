package com.mattymatty.audio_priority.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side sound pool overflow guard.
 *
 * <p>Adapted from audio_engine_tweaks 1.2.13 (Fabric, by mattymatty, LGPL-3.0), class
 * {@code com.mattymatty.audio_priority.mixins.SoundSystemMixin}.</p>
 *
 * <p>Vanilla {@code SoundEngine.play} asks {@code ChannelAccess.createHandle(...).join()} for a channel.
 * When the OpenAL source pool is exhausted the future completes with {@code null}. Upstream throws
 * {@code SoundPoolException} at that point so its own rewritten tick queue can skip the whole batch of
 * pending sounds - but that exception also escapes through the other call path
 * ({@code SoundManager.play -> ClientLevel.playLocalSound}) and crashes the client on the render thread.</p>
 *
 * <p>This port keeps the "ignore excess sounds when the pool is full" semantics without throwing: once the
 * pool has been observed full, further channel requests are short-circuited into an already-completed
 * {@code null} future (exactly what vanilla does with a failed request, so vanilla itself ignores that
 * sound), and the real pool is only probed again once every {@link #PROBE_INTERVAL_MS} milliseconds. This
 * removes the feedback loop where a fuller pool means more acquisition attempts per second.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SoundPoolGuard {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Minimum delay between two real pool probes while the pool is full (ms). */
    private static final long PROBE_INTERVAL_MS = 100L;

    /** Throttle interval for log messages (ms), to keep the log readable. */
    private static final long LOG_INTERVAL_MS = 5_000L;

    /** Vanilla {@code SoundEngine.MIN_SOURCE_LIFETIME}: 20 ticks. */
    private static final int DEFAULT_SOURCE_LIFETIME_TICKS = 20;

    /** Already-completed future holding {@code null}, equivalent to "no channel available". */
    private static final CompletableFuture<ChannelAccess.ChannelHandle> IGNORED_HANDLE =
            CompletableFuture.completedFuture(null);

    private static volatile boolean poolFull;
    private static volatile long lastProbeAt;
    private static long ignoredCount;
    private static long lastLogAt;

    private SoundPoolGuard() {
    }

    /**
     * Wraps the value returned by {@code ChannelAccess.createHandle}.
     *
     * @return an empty-handle future while requests are being ignored because the pool is full; otherwise
     *         the original future, with this probe's outcome recorded.
     */
    public static CompletableFuture<ChannelAccess.ChannelHandle> guardCreateHandle(
            CompletableFuture<ChannelAccess.ChannelHandle> future) {
        try {
            long now = System.currentTimeMillis();
            if (poolFull && now - lastProbeAt < PROBE_INTERVAL_MS) {
                ignoredCount++;
                logThrottled(now, true, "sound pool full, ignoring extra sound (ignored so far: "
                        + ignoredCount + ")");
                return IGNORED_HANDLE;
            }
            lastProbeAt = now;
            if (future == null) {
                return null;
            }
            // Record the outcome only after a real request; the callback runs on the sound executor thread,
            // so it must never throw (an exceptional future would surface at the vanilla call site).
            return future.thenApply(handle -> {
                try {
                    onProbeResult(handle);
                } catch (Throwable t) {
                    LOGGER.debug("[audio-pool-guard] failed to record pool state", t);
                }
                return handle;
            });
        } catch (Throwable t) {
            LOGGER.error("[audio-pool-guard] pool guard failed, falling back to vanilla behaviour", t);
            return future;
        }
    }

    private static void onProbeResult(ChannelAccess.ChannelHandle handle) {
        if (handle == null) {
            if (!poolFull) {
                poolFull = true;
                lastLogAt = System.currentTimeMillis();
                LOGGER.warn("[audio-pool-guard] sound pool is full (all OpenAL sources in use): "
                        + "extra sounds will be ignored until a channel frees up");
            }
        } else if (poolFull) {
            poolFull = false;
            LOGGER.info("[audio-pool-guard] sound pool recovered (ignored " + ignoredCount
                    + " sound(s) in this window)");
            ignoredCount = 0L;
        }
    }

    /**
     * Repairs the inconsistent state that crashes vanilla {@code SoundEngine.tickNonPaused()}.
     *
     * <p>Vanilla reads {@code soundDeleteTime.get(sound).intValue()} for every sound in
     * {@code instanceToChannel}; if a sound has no entry in the lifetime map this throws a
     * {@code NullPointerException} on the render thread. The two maps are only ever written as a pair by
     * vanilla, so this state means the pair went out of sync (e.g. an off-thread sound start interleaving
     * with the tick loop). Restoring the missing entry with the vanilla default lifetime lets vanilla do
     * its normal bookkeeping - the extra sound is dropped instead of crashing the game.</p>
     */
    public static void restoreMissingSoundLifetimes(Map<SoundInstance, ?> activeSounds,
                                                    Map<SoundInstance, Integer> soundDeleteTime,
                                                    int tickCount) {
        if (activeSounds.isEmpty()) {
            return;
        }
        int restored = 0;
        for (SoundInstance sound : activeSounds.keySet()) {
            if (!soundDeleteTime.containsKey(sound)) {
                soundDeleteTime.put(sound, tickCount + DEFAULT_SOURCE_LIFETIME_TICKS);
                restored++;
            }
        }
        if (restored > 0) {
            logThrottled(System.currentTimeMillis(), true, "restored " + restored
                    + " sound lifetime entr(ies) that vanilla would have NPE'd on");
        }
    }

    private static void logThrottled(long now, boolean warn, String message) {
        if (now - lastLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        if (warn) {
            LOGGER.warn("[audio-pool-guard] {}", message);
        } else {
            LOGGER.info("[audio-pool-guard] {}", message);
        }
    }
}
