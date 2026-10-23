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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;

/**
 * Generic version of the same "stop withdrawing more than needed" guard used by
 * {@link SpellIngredientGuardFeature} for sand/seaweed, but configurable by item name
 * instead of hardcoded to Superglass Make (e.g. capping Pot at 2), plus a
 * dedicated Volcanic ash line (withdraw in increments, e.g. 5 or 10 at a
 * time, capped at a total of 50).
 *
 * A withdraw option never overshoots the cap: a numbered option (Withdraw-1,
 * Withdraw-10, the last custom amount) or Withdraw-All is allowed only when
 * the amount it takes out is at most what is left under the cap.
 *
 * Tracks counts via {@link ItemContainerChanged} (the authoritative inventory
 * state) rather than re-reading the inventory widget at click time: the widget
 * lags a tick behind the click, so a live read lets rapid repeated withdrawals
 * slip past the cap. The count is also bumped optimistically on the click itself
 * so a second click in the same tick still sees an up-to-date value.
 */
@Slf4j
@Singleton
public class WithdrawLimitFeature implements Feature
{
    private final Map<Integer, Integer> trackedCounts = new HashMap<>();

    /**
     * Noted id to unnoted id for the dedicated items, so a withdraw-as-note
     * stack counts toward the same cap. Built on first use, on the client thread.
     */
    private Map<Integer, Integer> notedDedicated;

    /** Parsed Items list, replaced whole on config change and read on the client thread. */
    private volatile Map<String, Integer> limits = Map.of();

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    @Override
    public void startUp()
    {
        if (log.isDebugEnabled())
        {
            log.debug("[withdraw] [config] enabled={} limits={} ash={}/{} spade={} elkhorn={} pillar={} coralAmount={} blockMessages={}",
                config.withdrawLimitEnabled(), config.withdrawLimits().replace('\n', ' '),
                config.volcanicAshWithdrawLimitEnabled(), config.volcanicAshWithdrawLimitAmount(),
                config.spadeWithdrawLimitEnabled(), config.coralElkhornWithdrawLimitEnabled(),
                config.coralPillarWithdrawLimitEnabled(), config.coralWithdrawLimitAmount(),
                config.showBlockMessages());
        }
        limits = parseLimits(config.withdrawLimits());
        eventBus.register(this);
        // Nothing fires ItemContainerChanged until the next inventory change,
        // so starting up already logged in would count nothing carried.
        clientThread.invoke(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                primeCounts();
            }
        });
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        trackedCounts.clear();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (QolConfig.GROUP.equals(event.getGroup()) && "withdrawLimits".equals(event.getKey()))
        {
            limits = parseLimits(config.withdrawLimits());
            // Items newly on the list were skipped by the last count.
            clientThread.invoke(this::primeCounts);
        }
    }

    /** The inventory is null until the player exists, hence the guard. */
    private void primeCounts()
    {
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        if (inventory != null)
        {
            recount(inventory);
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() != InventoryID.INV)
        {
            return;
        }

        recount(event.getItemContainer());
    }

    private void recount(ItemContainer inventory)
    {
        Map<String, Integer> limits = this.limits;

        if (notedDedicated == null)
        {
            notedDedicated = new HashMap<>();
            for (int id : DEDICATED_ITEMS)
            {
                int noted = client.getItemDefinition(id).getLinkedNoteId();
                if (noted != -1)
                {
                    notedDedicated.put(noted, id);
                }
            }
        }

        trackedCounts.clear();
        for (Item item : inventory.getItems())
        {
            int id = item.getId();
            if (id <= 0)
            {
                continue;
            }
            if (isDedicatedItem(id))
            {
                trackedCounts.merge(id, item.getQuantity(), Integer::sum);
                continue;
            }
            Integer dedicatedBase = notedDedicated.get(id);
            if (dedicatedBase != null)
            {
                trackedCounts.merge(dedicatedBase, item.getQuantity(), Integer::sum);
                continue;
            }
            if (!limits.isEmpty())
            {
                ItemComposition composition = client.getItemDefinition(id);
                if (limits.containsKey(composition.getName().toLowerCase(Locale.ROOT)))
                {
                    // A noted stack counts under its unnoted id, the id a bank click carries.
                    int base = composition.getNote() != -1 ? composition.getLinkedNoteId() : id;
                    trackedCounts.merge(base, item.getQuantity(), Integer::sum);
                }
            }
        }
    }

    private static final int[] DEDICATED_ITEMS = {
        ItemID.FOSSIL_VOLCANIC_ASH, ItemID.SPADE, ItemID.CORAL_ELKHORN, ItemID.CORAL_PILLAR,
    };

    private boolean isDedicatedItem(int itemId)
    {
        return itemId == ItemID.FOSSIL_VOLCANIC_ASH
            || itemId == ItemID.SPADE
            || itemId == ItemID.CORAL_ELKHORN
            || itemId == ItemID.CORAL_PILLAR;
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        // Withdraw options only: Examine, Placeholder and the rest of the bank
        // item menu share this widget and have nothing to do with a cap.
        if (event.getParam1() != InterfaceID.Bankmain.ITEMS
            || !MenuText.isWithdrawOption(event.getMenuOption()))
        {
            return;
        }

        int itemId = event.getItemId();

        if (itemId == ItemID.FOSSIL_VOLCANIC_ASH && config.volcanicAshWithdrawLimitEnabled())
        {
            checkLimit(event, itemId, "WL: Farming", "Volcanic ash", config.volcanicAshWithdrawLimitAmount());
            return;
        }

        if (itemId == ItemID.SPADE && config.spadeWithdrawLimitEnabled())
        {
            checkLimit(event, itemId, "WL: Farming", "Spade", 1);
            return;
        }

        if (itemId == ItemID.CORAL_ELKHORN && config.coralElkhornWithdrawLimitEnabled())
        {
            checkLimit(event, itemId, "WL: Farming", "Elkhorn coral", config.coralWithdrawLimitAmount());
            return;
        }

        if (itemId == ItemID.CORAL_PILLAR && config.coralPillarWithdrawLimitEnabled())
        {
            checkLimit(event, itemId, "WL: Farming", "Pillar coral", config.coralWithdrawLimitAmount());
            return;
        }

        if (!config.withdrawLimitEnabled())
        {
            return;
        }

        Map<String, Integer> limits = this.limits;
        if (limits.isEmpty())
        {
            return;
        }

        ItemComposition item = client.getItemDefinition(itemId);
        Integer limit = limits.get(item.getName().toLowerCase(Locale.ROOT));
        if (limit == null)
        {
            return;
        }

        checkLimit(event, itemId, "Withdraw Limits", item.getName(), limit);
    }

    private void checkLimit(MenuOptionClicked event, int itemId, String prefix, String itemName, int limit)
    {
        int current = trackedCounts.getOrDefault(itemId, 0);
        if (current >= limit)
        {
            event.consume();
            if (log.isDebugEnabled())
            {
                log.debug("[withdraw] blocked withdraw of {} option={} current={} limit={}", itemName, event.getMenuOption(), current, limit);
            }
            if (config.showBlockMessages())
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[" + prefix + "] Enough " + itemName + " already.", null);
            }
            return;
        }

        int remaining = limit - current;
        int requested = MenuText.withdrawAmount(client, itemId, event.getMenuOption());
        if (requested > remaining)
        {
            event.consume();
            if (log.isDebugEnabled())
            {
                log.debug("[withdraw] blocked oversized withdraw of {} option={} current={} limit={}", itemName, event.getMenuOption(), current, limit);
            }
            if (config.showBlockMessages())
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[" + prefix + "] Only " + remaining + " more " + itemName + " allowed.", null);
            }
            return;
        }

        // The Withdraw-X prompt carries no number, so it counts as the full
        // headroom: safer to over-block the next click than let it through.
        if (requested < 0)
        {
            requested = remaining;
        }
        if (log.isDebugEnabled())
        {
            log.debug("[withdraw] allowed withdraw of {} option={} current={} limit={} counted={}", itemName, event.getMenuOption(), current, limit, requested);
        }
        trackedCounts.merge(itemId, requested, Integer::sum);
    }

    private Map<String, Integer> parseLimits(String config)
    {
        Map<String, Integer> limits = new HashMap<>();
        for (String entry : config.split("[,\\r\\n]"))
        {
            String[] parts = entry.split(":");
            if (parts.length != 2)
            {
                continue;
            }
            String name = parts[0].trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty())
            {
                continue;
            }
            try
            {
                int limit = Integer.parseInt(parts[1].trim());
                if (limit > 0)
                {
                    // A cap of zero or less would block the item outright, which
                    // reads as the guard being broken rather than configured.
                    limits.put(name, limit);
                }
            }
            catch (NumberFormatException ignored)
            {
                // malformed entry, skip it
            }
        }
        return Map.copyOf(limits);
    }
}
