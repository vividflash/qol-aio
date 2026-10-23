/*
 * Copyright (c) 2026, vividflash
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.vividflash.qol.features;

import com.vividflash.qol.QolConfig;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.SoundEffectID;
import net.runelite.api.WorldType;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;

/**
 * Mutes categories of repetitive/annoying sound effects, grouped using
 * {@link SoundEffectID} constants and toggled independently per category.
 * Consuming the event before the client plays it silences the sound.
 * <p>
 * Both {@link SoundEffectPlayed} and {@link AreaSoundEffectPlayed} carry a
 * (possibly null) {@code getSource()} actor. When "mute others only" is on, a
 * sound is only eligible for muting if it is sourced from another
 * <em>player</em>. NPC-sourced sounds and sounds with no source
 * (environment/ambient) are never muted in this mode, and your own sounds
 * stay audible even though they can arrive as an area sound whose source
 * happens to be you.
 * <p>
 * Safety exemption: when "never mute others in the Wilderness" is on (default),
 * a sound sourced from another <em>player</em> is never consumed while the
 * local player is in the Wilderness ({@code VarbitID.INSIDE_WILDERNESS}) or on
 * a PvP/Deadman world ({@link WorldType#isPvpWorld}), since another player's
 * noise there is a PKer warning. This overrides both the category toggles and "mute
 * others only". NPC-sourced, self-sourced, and sourceless sounds are not danger
 * cues and keep normal behavior. Sound events arrive on the client thread, so
 * the varbit is read directly per event.
 */
@Slf4j
@Singleton
public class SoundEffectMutesFeature implements Feature
{
    private static final int[] SKILLING = {
        SoundEffectID.TREE_CHOP,
        SoundEffectID.TREE_FALLING,
        SoundEffectID.MINING_TINK,
        SoundEffectID.COOK_WOOSH,
        SoundEffectID.SMITH_ANVIL_TINK,
        SoundEffectID.SMITH_ANVIL_TONK,
        SoundEffectID.TINDER_STRIKE,
        SoundEffectID.FIRE_WOOSH,
        SoundEffectID.PICK_PLANT_BLOOP,
        SoundEffectID.BURY_BONES,
        SoundEffectID.ITEM_PICKUP,
        SoundEffectID.ITEM_DROP,
        SoundEffectID.GE_INCREMENT_PLOP,
        SoundEffectID.GE_DECREMENT_PLOP,
        SoundEffectID.GE_ADD_OFFER_DINGALING,
        SoundEffectID.GE_COLLECT_BLOOP,
        SoundEffectID.GE_COIN_TINKLE,
    };

    private static final int[] PRAYER = {
        SoundEffectID.PRAYER_ACTIVATE_THICK_SKIN,
        SoundEffectID.PRAYER_ACTIVATE_BURST_OF_STRENGTH,
        SoundEffectID.PRAYER_ACTIVATE_CLARITY_OF_THOUGHT,
        SoundEffectID.PRAYER_ACTIVATE_SHARP_EYE_RIGOUR,
        SoundEffectID.PRAYER_ACTIVATE_MYSTIC_WILL_AUGURY,
        SoundEffectID.PRAYER_ACTIVATE_ROCK_SKIN,
        SoundEffectID.PRAYER_ACTIVATE_SUPERHUMAN_STRENGTH,
        SoundEffectID.PRAYER_ACTIVATE_IMPROVED_REFLEXES,
        SoundEffectID.PRAYER_ACTIVATE_RAPID_RESTORE_PRESERVE,
        SoundEffectID.PRAYER_ACTIVATE_RAPID_HEAL,
        SoundEffectID.PRAYER_ACTIVATE_PROTECT_ITEM,
        SoundEffectID.PRAYER_ACTIVATE_HAWK_EYE,
        SoundEffectID.PRAYER_ACTIVATE_MYSTIC_LORE,
        SoundEffectID.PRAYER_ACTIVATE_STEEL_SKIN,
        SoundEffectID.PRAYER_ACTIVATE_ULTIMATE_STRENGTH,
        SoundEffectID.PRAYER_ACTIVATE_INCREDIBLE_REFLEXES,
        SoundEffectID.PRAYER_ACTIVATE_PROTECT_FROM_MAGIC,
        SoundEffectID.PRAYER_ACTIVATE_PROTECT_FROM_MISSILES,
        SoundEffectID.PRAYER_ACTIVATE_PROTECT_FROM_MELEE,
        SoundEffectID.PRAYER_ACTIVATE_EAGLE_EYE,
        SoundEffectID.PRAYER_ACTIVATE_DEADEYE,
        SoundEffectID.PRAYER_ACTIVATE_MYSTIC_MIGHT,
        SoundEffectID.PRAYER_ACTIVATE_MYSTIC_VIGOUR,
        SoundEffectID.PRAYER_ACTIVATE_RETRIBUTION,
        SoundEffectID.PRAYER_ACTIVATE_REDEMPTION,
        SoundEffectID.PRAYER_ACTIVATE_SMITE,
        SoundEffectID.PRAYER_ACTIVATE_CHIVALRY,
        SoundEffectID.PRAYER_ACTIVATE_PIETY,
        SoundEffectID.PRAYER_DEACTIVE_VWOOP,
        SoundEffectID.PRAYER_DEPLETE_TWINKLE,
    };

    private static final int[] TOWN_CRIER = {
        SoundEffectID.TOWN_CRIER_BELL_DING,
        SoundEffectID.TOWN_CRIER_BELL_DONG,
        SoundEffectID.TOWN_CRIER_SHOUT_SQUEAK,
    };

    private static final int[] TELEPORT = {
        SoundEffectID.TELEPORT_VWOOP,
        SoundEffectID.NPC_TELEPORT_WOOSH,
    };

    private static final int[] NONE = new int[0];

    /** Sorted muted sound ids, rebuilt on config change. */
    private int[] muted = NONE;
    private boolean othersOnly;
    private boolean wildernessExempt;

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    @Override
    public void startUp()
    {
        rebuild();
        log.debug("[config] mutes skilling={} prayer={} townCrier={} teleport={} othersOnly={} wildernessExempt={}",
            config.muteSkilling(), config.mutePrayer(), config.muteTownCrier(), config.muteTeleport(),
            config.muteOthersOnly(), config.muteOthersWildernessExempt());
        eventBus.register(this);
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        muted = NONE;
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (QolConfig.GROUP.equals(event.getGroup()))
        {
            rebuild();
        }
    }

    @Subscribe
    public void onSoundEffectPlayed(SoundEffectPlayed event)
    {
        if (isMuted(event.getSoundId()) && shouldMute(event.getSource()))
        {
            event.consume();
        }
    }

    @Subscribe
    public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
    {
        if (isMuted(event.getSoundId()) && shouldMute(event.getSource()))
        {
            event.consume();
        }
    }

    /**
     * When "mute others only" is off, every matching sound is muted. When it's on, only sounds sourced from another player are
     * muted; NPC-sourced, sourceless, and your own sounds are left audible.
     * Regardless of either, the Wilderness/PvP safety exemption keeps other
     * players' sounds audible in dangerous areas.
     */
    private boolean shouldMute(Actor source)
    {
        if (wildernessExempt && isOtherPlayer(source) && inPvpDanger())
        {
            return false;
        }
        if (!othersOnly)
        {
            return true;
        }
        return isOtherPlayer(source);
    }

    private boolean isMuted(int soundId)
    {
        return muted.length != 0 && Arrays.binarySearch(muted, soundId) >= 0;
    }

    private boolean isOtherPlayer(Actor source)
    {
        return source instanceof Player && source != client.getLocalPlayer();
    }

    private boolean inPvpDanger()
    {
        return client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1
            || WorldType.isPvpWorld(client.getWorldType());
    }

    private void rebuild()
    {
        othersOnly = config.muteOthersOnly();
        wildernessExempt = config.muteOthersWildernessExempt();
        int[] ids = NONE;
        if (config.muteSkilling())
        {
            ids = add(ids, SKILLING);
        }
        if (config.mutePrayer())
        {
            ids = add(ids, PRAYER);
        }
        if (config.muteTownCrier())
        {
            ids = add(ids, TOWN_CRIER);
        }
        if (config.muteTeleport())
        {
            ids = add(ids, TELEPORT);
        }
        Arrays.sort(ids);
        muted = ids;
        log.debug("[mutes] muted sound set rebuilt: {} ids", ids.length);
    }

    private static int[] add(int[] ids, int[] more)
    {
        int[] out = Arrays.copyOf(ids, ids.length + more.length);
        System.arraycopy(more, 0, out, ids.length, more.length);
        return out;
    }
}
