package com.mattymatty.audio_priority.mixins;

import com.mattymatty.audio_priority.client.SoundPoolGuard;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Self-healing injection for the sound lifetime map.
 *
 * <p>Vanilla {@code SoundEngine.tickNonPaused()} (SRG: {@code m_120326_}) runs
 * {@code soundDeleteTime.get(sound).intValue()} for every entry of {@code instanceToChannel}. If a sound
 * has no entry in {@code soundDeleteTime} this throws a {@code NullPointerException} on the render thread.
 * Adding the missing entries at the start of the method turns that crash into a dropped sound.</p>
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    /** {@code SoundEngine.tickCount} */
    @Shadow
    private int f_120225_;

    /** {@code SoundEngine.instanceToChannel} */
    @Shadow
    @Final
    private Map<SoundInstance, ChannelAccess.ChannelHandle> f_120226_;

    /** {@code SoundEngine.soundDeleteTime} */
    @Shadow
    @Final
    private Map<SoundInstance, Integer> f_120230_;

    @Inject(method = "m_120326_", at = @At("HEAD"))
    private void audioPoolGuard$restoreMissingSoundLifetimes(CallbackInfo ci) {
        SoundPoolGuard.restoreMissingSoundLifetimes(f_120226_, f_120230_, f_120225_);
    }
}
