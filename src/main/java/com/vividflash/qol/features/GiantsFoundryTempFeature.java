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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

/**
 * Giants' Foundry safety guard. Two independent, fail-open blocks on the
 * crafting stations (Trip hammer / Grindstone / Polishing wheel):
 * <ul>
 *   <li><b>Stage-temperature mismatch</b> ({@code foundryTempGuard}): each
 *       crafting stage needs a specific heat band (hammer = high, grind = medium,
 *       polish = low). Working the preform while its temperature is outside the
 *       current stage's band gives no/negative progress and damages the sword, so
 *       the click is consumed. A stone-cold preform (temperature 0) falls outside
 *       every band and is therefore still covered.</li>
 *   <li><b>Bonus-window overwork</b> ({@code foundryOverworkGuard}): during a
 *       stage a periodic bonus ("sweetspot") window opens; clicking the station
 *       once more inside it grants extra progress, but clicking after it has
 *       closed overworks the metal for negative progress. Once the window has been
 *       seen to open and then close, the next station click is consumed
 *       until the following action resets the cycle.</li>
 * </ul>
 * Everything is fail-open: a click is only consumed when the stage, its required
 * band and the current temperature are all unambiguously known (mismatch block),
 * or when the bonus window was observed to close (overwork block). Any
 * unreadable widget/varbit lets the click through. Heating and quenching are
 * never matched, since those are the actions that bring the preform back into
 * the band a block is complaining about.
 */
@Slf4j
@Singleton
public class GiantsFoundryTempFeature implements Feature
{
    // The three stage bars double as the low/med/high heat-band reference widgets.
    private static final int SPRITE_TRIP_HAMMER = 4442;
    private static final int SPRITE_GRINDSTONE = 4443;
    private static final int SPRITE_POLISHING_WHEEL = 4444;

    // Bonus ("sweetspot") window: SWEETSPOT_LAYER child(0) glows this colour while open.
    private static final int BONUS_ACTIVE_TEXT_COLOR = 0xFCD703;

    private enum Stage
    {
        TRIP_HAMMER(Band.HIGH),
        GRINDSTONE(Band.MEDIUM),
        POLISHING_WHEEL(Band.LOW);

        private final Band band;

        Stage(Band band)
        {
            this.band = band;
        }
    }

    private enum Band
    {
        LOW,
        MEDIUM,
        HIGH,
        NONE
    }

    private static final Map<Band, Double> BAND_CENTERS = buildBandCenters();

    private static Map<Band, Double> buildBandCenters()
    {
        EnumMap<Band, Double> centers = new EnumMap<>(Band.class);
        centers.put(Band.LOW, 1 / 6d);
        centers.put(Band.MEDIUM, 3 / 6d);
        centers.put(Band.HIGH, 5 / 6d);
        return centers;
    }

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    // Overwork tracking. bonusWindowOpen mirrors the sweetspot widget state from tick
    // to tick; bonusExpired latches true when it transitions open -> closed and stays
    // true until the next station action, marking clicks that would overwork.
    private boolean bonusWindowOpen;
    private boolean bonusExpired;

    // Whether the Foundry HUD may be open, from its load/close events. Starts
    // true so a HUD already open at startup is found by the first tick's check.
    private boolean hudLoaded;

    // foundryOverworkGuard, cached for the per-tick check.
    private boolean overworkGuard;

    @Override
    public void startUp()
    {
        overworkGuard = config.foundryOverworkGuard();
        eventBus.register(this);
        bonusWindowOpen = false;
        bonusExpired = false;
        hudLoaded = true;
        log.debug("[foundry] [config] tempGuard={} overworkGuard={} blockMessages={}",
            config.foundryTempGuard(), overworkGuard, config.showBlockMessages());
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        bonusWindowOpen = false;
        bonusExpired = false;
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (QolConfig.GROUP.equals(event.getGroup()))
        {
            overworkGuard = config.foundryOverworkGuard();
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() == InterfaceID.GIANTS_FOUNDRY_HUD)
        {
            hudLoaded = true;
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        if (event.getGroupId() == InterfaceID.GIANTS_FOUNDRY_HUD)
        {
            // Leaving the Foundry ends the cycle: a latch kept until the next tick
            // would swallow the next matching click outside the Foundry.
            hudLoaded = false;
            bonusWindowOpen = false;
            bonusExpired = false;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        // Only the overwork guard uses the per-tick tracking; the latch is
        // also cleared by every unblocked station click.
        if (!overworkGuard || !hudLoaded || !atFoundry())
        {
            // Away from the station interface there is no cycle to track, and
            // a latch left set here would follow the player around the game.
            bonusWindowOpen = false;
            bonusExpired = false;
            return;
        }

        boolean open = isBonusWindowOpen();
        if (bonusWindowOpen && !open)
        {
            // The window seen open on an earlier tick has now closed: a repeat station click overworks.
            bonusExpired = true;
            log.debug("[foundry] bonus window closed, next station click will be blocked");
        }
        bonusWindowOpen = open;
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        MenuAction type = event.getMenuEntry().getType();
        boolean objectOption = type.getId() >= MenuAction.GAME_OBJECT_FIRST_OPTION.getId()
            && type.getId() <= MenuAction.GAME_OBJECT_FIFTH_OPTION.getId();
        if (!objectOption)
        {
            return;
        }

        String option = Text.removeTags(event.getMenuEntry().getOption()).toLowerCase();
        if (!isForgeAction(option))
        {
            return;
        }

        // Overwork block: window was open and has closed -> this repeat click overworks.
        if (overworkGuard && bonusExpired)
        {
            // Blocks once. Clearing the latch here keeps a second, deliberate
            // click on the same station from being swallowed as well.
            bonusExpired = false;
            event.consume();
            log.debug("[foundry] consumed '{}' click: bonus window closed (overwork)", option);
            if (config.showBlockMessages())
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "[Safety] The bonus window has closed - working again now overworks the metal.", null);
            }
            return;
        }

        // Stage/temperature-mismatch block.
        if (config.foundryTempGuard())
        {
            Stage stage = resolveStage();
            if (stage == null)
            {
                return; // fail open: unknown stage
            }

            Band current = getCurrentBand();
            if (current == null)
            {
                return; // fail open: could not read a temperature band
            }

            if (current != stage.band)
            {
                event.consume();
                log.debug("[foundry] consumed '{}' click: stage={} needs band={} but preform is in band={}",
                    option, stage, stage.band, current);
                if (config.showBlockMessages())
                {
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "[Safety] The preform is the wrong temperature for this stage - reheat/cool it first.", null);
                }
                return;
            }
        }

        // A genuine (non-blocked) action starts a fresh cycle for the overwork tracker.
        bonusExpired = false;
    }

    /**
     * The three station actions the guards apply to. Heating and quenching are
     * excluded on purpose: they change the temperature rather than work the
     * metal, and blocking them would leave no way out of a band mismatch.
     */
    private boolean isForgeAction(String option)
    {
        if (option.contains("heat") || option.contains("quench") || option.contains("cool"))
        {
            return false;
        }
        return option.contains("hammer") || option.contains("grind")
            || option.contains("polish") || option.contains("work");
    }

    /** True while the Foundry's crafting HUD is on screen. */
    private boolean atFoundry()
    {
        // A missing HUD also clears the load flag, so a close event that never
        // arrived costs one lookup instead of one per tick.
        hudLoaded = client.getWidget(InterfaceID.GiantsFoundryHud.COMPLETION_BAR) != null;
        return hudLoaded;
    }

    /**
     * Current stage of the sword, derived from completion progress and the ordered
     * list of stages shown on the completion bar. Returns null when it cannot be
     * determined so callers fail open.
     */
    private Stage resolveStage()
    {
        Widget completionBar = client.getWidget(InterfaceID.GiantsFoundryHud.COMPLETION_BAR);
        if (completionBar == null || completionBar.getChildren() == null)
        {
            return null;
        }

        List<Stage> orderedStages = new ArrayList<>();
        for (Widget child : completionBar.getChildren())
        {
            Stage mapped = child != null ? stageForSprite(child.getSpriteId()) : null;
            if (mapped != null)
            {
                orderedStages.add(mapped);
            }
        }

        int stageCount = orderedStages.size();
        if (stageCount == 0)
        {
            return null;
        }

        int completionProgress = client.getVarbitValue(VarbitID.GIANTS_FOUNDRY_PREFORM_COMPLETION);
        // Progress runs 0..1000 across the stage bar.
        int selectedIndex = (completionProgress * stageCount) / 1000;
        if (selectedIndex < 0 || selectedIndex >= stageCount)
        {
            return null;
        }
        return orderedStages.get(selectedIndex);
    }

    private static Stage stageForSprite(int spriteId)
    {
        if (spriteId == SPRITE_TRIP_HAMMER)
        {
            return Stage.TRIP_HAMMER;
        }
        if (spriteId == SPRITE_GRINDSTONE)
        {
            return Stage.GRINDSTONE;
        }
        if (spriteId == SPRITE_POLISHING_WHEEL)
        {
            return Stage.POLISHING_WHEEL;
        }
        return null;
    }

    /**
     * Which heat band the preform currently sits in. Returns NONE when it is
     * outside every band (includes a stone-cold, temperature-0 preform), or null
     * when the band widths could not be read (fail open).
     */
    private Band getCurrentBand()
    {
        double bandWidth = getBandRatio();
        if (bandWidth <= 0)
        {
            return null;
        }

        int currentHeat = client.getVarbitValue(VarbitID.GIANTS_FOUNDRY_PREFORM_TEMPERATURE);

        for (Map.Entry<Band, Double> entry : BAND_CENTERS.entrySet())
        {
            if (withinBand(currentHeat, entry.getValue(), bandWidth))
            {
                return entry.getKey();
            }
        }
        return Band.NONE;
    }

    /**
     * Whether an integer 0..1000 heat value falls within a band spanning
     * {@code bandWidth} (as a fraction of the 0..1000 scale) around {@code center}.
     */
    private boolean withinBand(int heat, double center, double bandWidth)
    {
        double halfWidth = bandWidth / 2;
        long lowerBound = (long) Math.floor((center - halfWidth) * 1000);
        long upperBound = (long) Math.floor((center + halfWidth) * 1000);
        return heat >= lowerBound && heat <= upperBound;
    }

    /**
     * Width of a heat band as a fraction of the whole temperature bar. The bands
     * are all the same width; the medium (grind) bar is used as the sample.
     * Returns 0 when the widgets are unreadable.
     */
    private double getBandRatio()
    {
        Widget fullTemperatureBar = client.getWidget(InterfaceID.GiantsFoundryHud.TEMPERATURE_BAR_RECT1);
        if (fullTemperatureBar == null)
        {
            return 0;
        }

        int fullBarWidth = fullTemperatureBar.getWidth();
        if (fullBarWidth <= 0)
        {
            return 0;
        }

        Widget grindBandWidget = client.getWidget(InterfaceID.GiantsFoundryHud.GRIND_BAR);
        if (grindBandWidget == null)
        {
            return 0;
        }

        return grindBandWidget.getWidth() / (double) fullBarWidth;
    }

    private boolean isBonusWindowOpen()
    {
        Widget sweetspot = client.getWidget(InterfaceID.GiantsFoundryHud.SWEETSPOT_LAYER);
        if (sweetspot == null)
        {
            return false;
        }

        Widget[] sweetspotChildren = sweetspot.getChildren();
        if (sweetspotChildren == null || sweetspotChildren.length == 0)
        {
            return false;
        }

        Widget indicator = sweetspot.getChild(0);
        if (indicator == null)
        {
            return false;
        }

        return indicator.getTextColor() == BONUS_ACTIVE_TEXT_COLOR;
    }
}
