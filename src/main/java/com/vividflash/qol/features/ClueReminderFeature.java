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
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.game.ItemManager;

/**
 * Reminder (not a blocker): once you hold at least the configured number of clue
 * scrolls of a single tier, posts a one-time chat nudge to go do them. Fires once
 * per tier; re-arms when that tier's held count drops back below the threshold.
 * Tier detection is name-based ("clue scroll (tier)") so no clue item IDs are baked in.
 */
@Slf4j
@Singleton
public class ClueReminderFeature implements Feature
{
    private enum Tier
    {
        BEGINNER("beginner"),
        EASY("easy"),
        MEDIUM("medium"),
        HARD("hard"),
        ELITE("elite"),
        MASTER("master");

        private final String keyword;

        Tier(String keyword)
        {
            this.keyword = keyword;
        }
    }

    private static final Tier[] TIERS = Tier.values();
    private static final String CLUE_PREFIX = "clue scroll";

    private final Map<Tier, Boolean> reminded = new EnumMap<>(Tier.class);

    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    @Override
    public void startUp()
    {
        reminded.clear();
        log.debug("[config] enabled={} threshold={}", config.clueReminder(), config.clueReminderThreshold());
        eventBus.register(this);
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        reminded.clear();
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() != InventoryID.INV || !config.clueReminder())
        {
            return;
        }

        Map<Tier, Integer> counts = countClues(event.getItemContainer());
        int threshold = config.clueReminderThreshold();

        for (Tier tier : TIERS)
        {
            int count = counts.getOrDefault(tier, 0);
            boolean alreadyReminded = reminded.getOrDefault(tier, false);

            if (count >= threshold && !alreadyReminded)
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "[Clues] You're holding " + count + " " + tier.keyword
                        + " clue scrolls - maybe knock some out.", null);
                log.debug("[clue] reminder posted: tier={} count={} threshold={}", tier, count, threshold);
                reminded.put(tier, true);
            }
            else if (count < threshold && alreadyReminded)
            {
                log.debug("[clue] reminder re-armed: tier={} count={} threshold={}", tier, count, threshold);
                reminded.put(tier, false);
            }
        }
    }

    private Map<Tier, Integer> countClues(ItemContainer container)
    {
        Map<Tier, Integer> counts = new EnumMap<>(Tier.class);
        if (container == null)
        {
            return counts;
        }
        for (Item item : container.getItems())
        {
            if (item.getId() < 0)
            {
                continue;
            }
            String name = itemManager.getItemComposition(item.getId()).getName();
            if (name == null)
            {
                continue;
            }
            if (!name.regionMatches(true, 0, CLUE_PREFIX, 0, CLUE_PREFIX.length()))
            {
                continue;
            }
            String lower = name.toLowerCase();
            for (Tier tier : TIERS)
            {
                if (lower.contains(tier.keyword))
                {
                    counts.merge(tier, 1, Integer::sum);
                    break;
                }
            }
        }
        return counts;
    }
}
