package com.mattymatty.audio_priority;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Audio Pool Guard - a stripped Forge port of audio_engine_tweaks (Fabric, by mattymatty, LGPL-3.0).
 *
 * <p>Only one feature of the upstream mod is kept: "ignore excess sounds once the client sound pool is
 * full" (upstream {@code SoundSystemMixin#onAcquireSourceManager} together with
 * {@code play_current_tick_sounds}). Everything else was removed: category priority sorting,
 * per-category pool usage limits, duplicate sound limits, per-category volume multipliers,
 * the instant-category whitelist, all configuration screens and the ModMenu integration.</p>
 *
 * <p>The actual logic lives in {@code mixins.ChannelAccessMixin}, {@code mixins.SoundEngineMixin} and
 * {@code client.SoundPoolGuard}. This class is only the Forge mod entry point; it references no client
 * class, so a dedicated server can load it safely.</p>
 */
@Mod(AudioPoolGuard.MODID)
public class AudioPoolGuard {

    /**
     * Forge mod id. Must match {@code [a-z][a-z0-9_]{1,63}} - dashes are rejected by Forge 1.20.1
     * ("File &lt;jar&gt; is not a valid mod file"), so this differs from the project/artifact name.
     */
    public static final String MODID = "audio_pool_guard";

    public static final Logger LOGGER = LogUtils.getLogger();

    public AudioPoolGuard() {
        LOGGER.info("[audio-pool-guard] sound pool overflow guard loaded (client side only)");
    }
}
