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

import com.vividflash.qol.PoisonFlashMode;
import com.vividflash.qol.QolConfig;
import com.vividflash.qol.RepotFlashStyle;
import java.awt.Color;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * "Repot flash": flashes the matching potions in the inventory (and/or the
 * whole inventory) when a protective, timer-based potion effect is about to
 * run out. It covers the effects where forgetting to repot can kill you:
 * antifires, poison/venom immunity, overloads (NMZ / CoX / DMM), NMZ
 * absorption points and Menaphite remedy. Stat-boost decay potions are
 * deliberately excluded.
 *
 * The underlying varbits only update once per effect "pulse" (12-18 s), and
 * that granularity is all the warning needs: the flash starts when an effect
 * reaches its final pulse, meaning it expires within the current pulse window.
 * Potions are matched by item name, not hardcoded IDs.
 */
@Slf4j
@Singleton
public class RepotFlashFeature implements Feature
{
    /** Poison varp values below this mean venom immunity, above poison-only. */
    private static final int VENOM_CUTOFF = -38;

    /** NMZ arena map region (shared with the KBD lair, which is on plane 0). */
    private static final int[] NMZ_MAP_REGION = {9033};

    private static final int TICK_MS = 600;

    // Potion name match bits, one per name family.
    private static final int MATCH_ANTIFIRE = 1;
    private static final int MATCH_SUPER_ANTIFIRE = 1 << 1;
    private static final int MATCH_POISON = 1 << 2;
    private static final int MATCH_VENOM = 1 << 3;
    private static final int MATCH_OVERLOAD = 1 << 4;
    private static final int MATCH_MENAPHITE = 1 << 5;
    private static final int MATCH_ABSORPTION = 1 << 6;

    private static final int INVENTORY_SIZE = 28;

    enum Effect
    {
        // Base + extended antifire share one varbit; ditto the super pair.
        // Pulse windows: antifire 18 s, super antifire 12 s, poison/venom
        // 18 s, overload and Menaphite 15 s.
        ANTIFIRE,
        SUPER_ANTIFIRE,
        POISON_VENOM,
        // NMZ, CoX and DMM blighted overloads each have their own varbit but
        // behave identically; one state covers all three since the player
        // can only ever be in one of those places.
        OVERLOAD,
        MENAPHITE
    }

    private static final class EffectState
    {
        int pulsesLeft;
        int expiredAtTick = -1;
        boolean active;
    }

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private EventBus eventBus;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private QolConfig config;

    @Inject
    private RepotFlashItemOverlay itemOverlay;

    @Inject
    private RepotFlashInventoryOverlay inventoryOverlay;

    private final Map<Effect, EffectState> states = new EnumMap<>(Effect.class);
    private final Map<Integer, Integer> itemMatches = new HashMap<>();

    private int rawPoisonValue;
    private int absorptionPoints;
    private PoisonFlashMode trackedPoisonMode;

    // Config mirrors, written by onConfigChanged (EDT) and read on the client
    // thread and by the overlays, hence volatile.
    private volatile boolean antifireEnabled;
    private volatile boolean superAntifireEnabled;
    private volatile PoisonFlashMode poisonMode;
    private volatile boolean overloadEnabled;
    private volatile boolean menaphiteEnabled;
    private volatile boolean absorptionEnabled;
    private volatile int absorptionThreshold;
    private volatile int graceTicks;
    private volatile RepotFlashStyle style;
    private volatile long flashIntervalMs;
    private volatile Color color1;
    private volatile Color color2;
    private volatile Color border1;
    private volatile Color border2;

    // Distinct carried repot targets with their name match bits, rebuilt on
    // inventory changes so the per-tick check never touches item names.
    private final int[] carriedIds = new int[INVENTORY_SIZE];
    private final int[] carriedMatches = new int[INVENTORY_SIZE];
    private int carriedCount;
    private int carriedMatchAll;
    private boolean inventoryLoaded;

    // Item ids flashing right now: computed per tick and on inventory
    // changes, read by the overlays every frame.
    private final int[] flashIds = new int[INVENTORY_SIZE];
    private int flashCount;

    @Override
    public void startUp()
    {
        for (Effect effect : Effect.values())
        {
            states.put(effect, new EffectState());
        }
        loadConfig();
        trackedPoisonMode = poisonMode;
        inventoryLoaded = false;
        log.debug("[config] antifire={} superAntifire={} poisonMode={} overload={} menaphite={} absorption={}"
                + " absorptionThreshold={} graceSeconds={} style={}",
            antifireEnabled, superAntifireEnabled, trackedPoisonMode,
            overloadEnabled, menaphiteEnabled, absorptionEnabled,
            absorptionThreshold, config.repotFlashGraceSeconds(), style);
        eventBus.register(this);
        overlayManager.add(itemOverlay);
        overlayManager.add(inventoryOverlay);
        // Varbits only fire on change, so starting up logged in would miss a
        // running effect until its next pulse and read 0 absorption points.
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            clientThread.invoke(this::primeEffects);
        }
    }

    private void loadConfig()
    {
        antifireEnabled = config.repotFlashAntifire();
        superAntifireEnabled = config.repotFlashSuperAntifire();
        poisonMode = config.repotFlashPoisonMode();
        overloadEnabled = config.repotFlashOverload();
        menaphiteEnabled = config.repotFlashMenaphite();
        absorptionEnabled = config.repotFlashAbsorption();
        absorptionThreshold = config.repotFlashAbsorptionThreshold();
        graceTicks = secondsToTicks(config.repotFlashGraceSeconds());
        style = config.repotFlashStyle();
        flashIntervalMs = Math.max(100, config.repotFlashIntervalMs());
        Color c1 = config.repotFlashColor1();
        Color c2 = config.repotFlashColor2();
        border1 = new Color(c1.getRed(), c1.getGreen(), c1.getBlue(), 220);
        border2 = new Color(c2.getRed(), c2.getGreen(), c2.getBlue(), 220);
        color1 = c1;
        color2 = c2;
    }

    private void primeEffects()
    {
        if (states.isEmpty())
        {
            return;
        }
        update(Effect.ANTIFIRE, client.getVarbitValue(VarbitID.ANTIFIRE_POTION));
        update(Effect.SUPER_ANTIFIRE, client.getVarbitValue(VarbitID.SUPER_ANTIFIRE_POTION));
        update(Effect.OVERLOAD, Math.max(client.getVarbitValue(VarbitID.NZONE_OVERLOAD_POTION_EFFECTS),
            Math.max(client.getVarbitValue(VarbitID.RAIDS_OVERLOAD_TIMER),
                client.getVarbitValue(VarbitID.DEADMAN_OVERLOAD_POTION_EFFECTS))));
        update(Effect.MENAPHITE, client.getVarbitValue(VarbitID.STATRENEWAL_POTION_TIMER));
        absorptionPoints = client.getVarbitValue(VarbitID.NZONE_ABSORB_POTION_EFFECTS);
        rawPoisonValue = client.getVarpValue(VarPlayerID.POISON);
        update(Effect.POISON_VENOM, poisonPulses(rawPoisonValue, trackedPoisonMode));
    }

    @Override
    public void shutDown()
    {
        overlayManager.remove(itemOverlay);
        overlayManager.remove(inventoryOverlay);
        eventBus.unregister(this);
        states.clear();
        itemMatches.clear();
        rawPoisonValue = 0;
        absorptionPoints = 0;
        carriedCount = 0;
        carriedMatchAll = 0;
        flashCount = 0;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case LOGIN_SCREEN:
            case HOPPING:
                // Varps resync on login; drop stale state so an old expiry
                // can't keep the grace-period flash alive across a hop.
                log.debug("[repot] effect state reset on {}", event.getGameState());
                for (EffectState state : states.values())
                {
                    state.active = false;
                    state.pulsesLeft = 0;
                    state.expiredAtTick = -1;
                }
                rawPoisonValue = 0;
                absorptionPoints = 0;
                flashCount = 0;
                break;
            default:
                break;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (QolConfig.GROUP.equals(event.getGroup()))
        {
            loadConfig();
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        int varbit = event.getVarbitId();
        int value = event.getValue();

        if (varbit == VarbitID.ANTIFIRE_POTION)
        {
            update(Effect.ANTIFIRE, value);
        }
        else if (varbit == VarbitID.SUPER_ANTIFIRE_POTION)
        {
            update(Effect.SUPER_ANTIFIRE, value);
        }
        else if (varbit == VarbitID.NZONE_OVERLOAD_POTION_EFFECTS
            || varbit == VarbitID.RAIDS_OVERLOAD_TIMER
            || varbit == VarbitID.DEADMAN_OVERLOAD_POTION_EFFECTS)
        {
            update(Effect.OVERLOAD, value);
        }
        else if (varbit == VarbitID.STATRENEWAL_POTION_TIMER)
        {
            update(Effect.MENAPHITE, value);
        }
        else if (varbit == VarbitID.NZONE_ABSORB_POTION_EFFECTS)
        {
            absorptionPoints = value;
        }
        else if (event.getVarpId() == VarPlayerID.POISON)
        {
            rawPoisonValue = value;
            update(Effect.POISON_VENOM, poisonPulses(value, poisonMode));
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() != InventoryID.INV)
        {
            return;
        }
        loadInventory(event.getItemContainer());
        // Recompute right away so the next dose (a new item id) keeps
        // flashing without a one-tick gap.
        updateFlash();
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (!inventoryLoaded)
        {
            loadInventory(client.getItemContainer(InventoryID.INV));
        }
        updateFlash();
    }

    private void loadInventory(ItemContainer inventory)
    {
        inventoryLoaded = true;
        carriedCount = 0;
        carriedMatchAll = 0;
        if (inventory == null)
        {
            return;
        }
        for (Item item : inventory.getItems())
        {
            int id = item.getId();
            if (id <= 0 || carriedCount == INVENTORY_SIZE || indexOf(carriedIds, carriedCount, id) >= 0)
            {
                continue;
            }
            int matches = itemMatches(id);
            if (matches == 0)
            {
                continue;
            }
            carriedIds[carriedCount] = id;
            carriedMatches[carriedCount] = matches;
            carriedCount++;
            carriedMatchAll |= matches;
        }
        if (carriedCount > 0)
        {
            log.debug("[repot] inventory scanned: {} distinct repot targets, matchBits={}",
                carriedCount, carriedMatchAll);
        }
    }

    /**
     * Rebuilds the flashing item ids. Free when no repot target is carried;
     * the NMZ location check only runs while an absorption potion is.
     */
    private void updateFlash()
    {
        flashCount = 0;
        if (carriedMatchAll == 0)
        {
            return;
        }

        int active = 0;
        if ((carriedMatchAll & MATCH_ANTIFIRE) != 0 && antifireFlashing())
        {
            active |= MATCH_ANTIFIRE;
        }
        if ((carriedMatchAll & MATCH_SUPER_ANTIFIRE) != 0 && superAntifireFlashing())
        {
            active |= MATCH_SUPER_ANTIFIRE;
        }
        if ((carriedMatchAll & (MATCH_POISON | MATCH_VENOM)) != 0 && poisonFlashing())
        {
            active |= poisonMode == PoisonFlashMode.VENOM ? MATCH_VENOM : MATCH_POISON;
        }
        if ((carriedMatchAll & MATCH_OVERLOAD) != 0 && overloadFlashing())
        {
            active |= MATCH_OVERLOAD;
        }
        if ((carriedMatchAll & MATCH_MENAPHITE) != 0 && menaphiteFlashing())
        {
            active |= MATCH_MENAPHITE;
        }
        if ((carriedMatchAll & MATCH_ABSORPTION) != 0 && absorptionFlashing())
        {
            active |= MATCH_ABSORPTION;
        }
        if (active == 0)
        {
            return;
        }

        for (int i = 0; i < carriedCount; i++)
        {
            if ((carriedMatches[i] & active) != 0)
            {
                flashIds[flashCount++] = carriedIds[i];
            }
        }
    }

    private static int indexOf(int[] ids, int count, int id)
    {
        for (int i = 0; i < count; i++)
        {
            if (ids[i] == id)
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Remaining immunity pulses on the shared poison varp for the watched
     * mode. Positive varp values are poison damage, not immunity.
     */
    private static int poisonPulses(int varpValue, PoisonFlashMode mode)
    {
        switch (mode)
        {
            case POISON:
                return varpValue < 0 ? -varpValue : 0;
            case VENOM:
                return varpValue < VENOM_CUTOFF ? VENOM_CUTOFF - varpValue : 0;
            default:
                return 0;
        }
    }

    private void update(Effect effect, int pulsesLeft)
    {
        EffectState state = states.get(effect);
        if (state == null)
        {
            return;
        }
        if (pulsesLeft > 0)
        {
            if (!state.active)
            {
                log.debug("[repot] {} became active: pulsesLeft={}", effect, pulsesLeft);
            }
            else if (pulsesLeft <= 1 && state.pulsesLeft > 1)
            {
                log.debug("[repot] {} entered final pulse, flash window open", effect);
            }
            state.pulsesLeft = pulsesLeft;
            state.active = true;
            state.expiredAtTick = -1;
        }
        else
        {
            if (state.active)
            {
                state.expiredAtTick = client.getTickCount();
                log.debug("[repot] {} expired at tick {}, grace {} ticks", effect, state.expiredAtTick,
                    graceTicks);
            }
            state.active = false;
            state.pulsesLeft = 0;
        }
    }

    private boolean flashing(Effect effect)
    {
        EffectState state = states.get(effect);
        if (state == null)
        {
            return false;
        }

        // Switching the poison dropdown re-reads immunity from the raw varp
        // so the state doesn't keep the old mode's semantics until the next
        // (up to 18 s away) pulse.
        if (effect == Effect.POISON_VENOM && trackedPoisonMode != poisonMode)
        {
            trackedPoisonMode = poisonMode;
            state.expiredAtTick = -1;
            update(effect, poisonPulses(rawPoisonValue, trackedPoisonMode));
        }

        if (state.active)
        {
            // Final pulse: the effect ends within the current pulse window,
            // so it's time to repot. No finer timing needed.
            return state.pulsesLeft <= 1;
        }
        // Keep flashing for a grace window after expiry, which is when the
        // repot matters most, unless the effect was never active.
        return state.expiredAtTick >= 0
            && client.getTickCount() - state.expiredAtTick <= graceTicks;
    }

    private static int secondsToTicks(int seconds)
    {
        return (seconds * 1000 + TICK_MS - 1) / TICK_MS;
    }

    private boolean antifireFlashing()
    {
        return antifireEnabled && flashing(Effect.ANTIFIRE);
    }

    private boolean superAntifireFlashing()
    {
        return superAntifireEnabled && flashing(Effect.SUPER_ANTIFIRE);
    }

    private boolean poisonFlashing()
    {
        return poisonMode != PoisonFlashMode.OFF
            && flashing(Effect.POISON_VENOM);
    }

    private boolean overloadFlashing()
    {
        return overloadEnabled && flashing(Effect.OVERLOAD);
    }

    private boolean menaphiteFlashing()
    {
        return menaphiteEnabled && flashing(Effect.MENAPHITE);
    }

    /**
     * Absorption is a damage-point pool, not a timer: flash whenever the pool
     * is at or below the threshold while in the NMZ arena (including 0, the
     * moment it matters most). The region gate keeps banks flash-free.
     */
    private boolean absorptionFlashing()
    {
        return absorptionEnabled
            && absorptionPoints <= absorptionThreshold
            && inNmzArena();
    }

    private boolean inNmzArena()
    {
        Player local = client.getLocalPlayer();
        WorldView worldView = client.getTopLevelWorldView();
        return local != null
            && worldView != null
            && local.getWorldLocation().getPlane() > 0
            && Arrays.equals(worldView.getMapRegions(), NMZ_MAP_REGION);
    }

    /**
     * Whether this inventory item is a repot target of an effect that is
     * currently in its warning window. Called per item per frame by the item
     * overlay, so it only reads the per-tick result.
     */
    boolean shouldFlashItem(int itemId)
    {
        return flashCount > 0 && indexOf(flashIds, flashCount, itemId) >= 0;
    }

    /**
     * Whether any effect is flashing AND a matching potion is carried: the
     * gate for the full-inventory flash (no potion, nothing to click).
     */
    boolean anyInventoryTarget()
    {
        return flashCount > 0;
    }

    private static int nameMatches(String name)
    {
        if (name.isEmpty())
        {
            return 0;
        }
        int matches = 0;
        if (matchesAntifire(name))
        {
            matches |= MATCH_ANTIFIRE;
        }
        if (matchesSuperAntifire(name))
        {
            matches |= MATCH_SUPER_ANTIFIRE;
        }
        if (matchesPoison(name))
        {
            matches |= MATCH_POISON;
        }
        if (matchesVenom(name))
        {
            matches |= MATCH_VENOM;
        }
        if (matchesOverload(name))
        {
            matches |= MATCH_OVERLOAD;
        }
        if (matchesMenaphite(name))
        {
            matches |= MATCH_MENAPHITE;
        }
        if (matchesAbsorption(name))
        {
            matches |= MATCH_ABSORPTION;
        }
        return matches;
    }

    RepotFlashStyle flashStyle()
    {
        return style;
    }

    /** Which half of the two-color alternation shows now; read once per frame. */
    boolean flashFirstColor()
    {
        return (System.currentTimeMillis() / flashIntervalMs) % 2 == 0;
    }

    Color flashColor(boolean first)
    {
        return first ? color1 : color2;
    }

    Color flashBorderColor(boolean first)
    {
        return first ? border1 : border2;
    }

    /** Name match bits per item id, cached so a name is classified once. */
    private int itemMatches(int itemId)
    {
        Integer cached = itemMatches.get(itemId);
        if (cached == null)
        {
            ItemComposition composition = client.getItemDefinition(itemId);
            cached = composition == null ? 0 : nameMatches(composition.getName().toLowerCase());
            itemMatches.put(itemId, cached);
        }
        return cached;
    }

    // Name matching deliberately uses contains(): potion names vary in dose
    // suffix spacing ("Overload (4)" vs "Antipoison(4)"), and extended
    // variants embed the base name.

    private static boolean matchesAntifire(String name)
    {
        return name.contains("antifire") && !name.contains("super");
    }

    private static boolean matchesSuperAntifire(String name)
    {
        return name.contains("super antifire");
    }

    private static boolean matchesPoison(String name)
    {
        return name.contains("antipoison") || name.contains("antidote")
            || name.contains("sanfew serum") || name.contains("anti-venom");
    }

    private static boolean matchesVenom(String name)
    {
        return name.contains("anti-venom") || name.contains("antidote++");
    }

    private static boolean matchesOverload(String name)
    {
        return name.contains("overload");
    }

    private static boolean matchesMenaphite(String name)
    {
        return name.contains("menaphite remedy");
    }

    private static boolean matchesAbsorption(String name)
    {
        return name.contains("absorption");
    }
}
