package com.mattymatty.audio_priority.mixins;

import com.mattymatty.audio_priority.client.SoundPoolGuard;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Injection point for "ignore excess sounds once the sound pool is full".
 *
 * <p>This is the counterpart of upstream {@code SoundSystemMixin#onAcquireSourceManager}, which wrapped
 * {@code CompletableFuture.join()}. Here the same decision is made one step earlier, on the return value
 * of {@code ChannelAccess.createHandle}, so no exception can escape to the caller.</p>
 *
 * <p>The target is written as an SRG member name (the runtime name in Forge 1.20.1, where class names are
 * official Mojang names but member names are SRG). No refmap and no Mixin annotation processor are used;
 * see the README for the trade-off.</p>
 */
@Mixin(ChannelAccess.class)
public abstract class ChannelAccessMixin {

    @Inject(method = "m_120128_", at = @At("RETURN"), cancellable = true)
    private void audioPoolGuard$guardCreateHandle(
            CallbackInfoReturnable<CompletableFuture<ChannelAccess.ChannelHandle>> cir) {
        cir.setReturnValue(SoundPoolGuard.guardCreateHandle(cir.getReturnValue()));
    }
}
