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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Fossil Island birdhouse trip loadout: caps the one-off equipment (Chisel,
 * Hammer, Digsite pendant, Clockwork) at 1 each under one shared toggle, and
 * caps each birdhouse log type independently (own toggle, shared max amount).
 *
 * A tool variant shares its base tool's cap, since either one does the job
 * and carrying both is the misclick: a jeweller's chisel counts as a chisel,
 * an Imcando hammer as a hammer, the offhand version included. The broken
 * Imcando is left out: it does nothing until repaired, and it is not
 * something normally kept in the bank.
 *
 * The clockwork cap is 1 regardless of the log amount: the placed houses
 * each already hold their own mechanism, so a run carries one spare to craft
 * the next house between spots.
 *
 * Counts are tracked via ItemContainerChanged rather than read live from the
 * widget, since a live read lags a tick behind the click and lets repeated
 * withdrawals slip past the cap. A numbered or Withdraw-All option that
 * takes out more than is left under the cap is blocked, so a cap is never
 * overshot.
 */
@Slf4j
@Singleton
public class BirdhouseWithdrawLimitFeature implements Feature
{
    private static final class EquipmentGroup
    {
        final String name;
        final int[] itemIds;

        EquipmentGroup(String name, int... itemIds)
        {
            this.name = name;
            this.itemIds = itemIds;
        }
    }

    private final EquipmentGroup[] equipmentGroups = {
        new EquipmentGroup("Chisel", ItemID.CHISEL, ItemID.JEWELLERS_CHISEL),
        new EquipmentGroup(
            "Hammer",
            ItemID.HAMMER,
            ItemID.IMCANDO_HAMMER,
            ItemID.IMCANDO_HAMMER_OFFHAND
        ),
        new EquipmentGroup("Clockwork", ItemID.POH_CLOCKWORK_MECHANISM),
        new EquipmentGroup(
            "Digsite pendant",
            ItemID.NECKLACE_OF_DIGSITE,
            ItemID.NECKLACE_OF_DIGSITE_1,
            ItemID.NECKLACE_OF_DIGSITE_2,
            ItemID.NECKLACE_OF_DIGSITE_3,
            ItemID.NECKLACE_OF_DIGSITE_4,
            ItemID.NECKLACE_OF_DIGSITE_5
        ),
    };

    private final Set<Integer> trackedItems = new HashSet<>();
    private final Map<Integer, Integer> trackedCounts = new HashMap<>();

    /**
     * Noted id to unnoted id for the tracked items, so a withdraw-as-note
     * stack counts toward the same cap. Built on first use, on the client thread.
     */
    private Map<Integer, Integer> notedToBase;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    public BirdhouseWithdrawLimitFeature()
    {
        for (EquipmentGroup group : equipmentGroups)
        {
            for (int id : group.itemIds)
            {
                trackedItems.add(id);
            }
        }
        for (int id : logItemIds())
        {
            trackedItems.add(id);
        }
    }

    private int[] logItemIds()
    {
        return new int[] {
            ItemID.LOGS,
            ItemID.OAK_LOGS,
            ItemID.WILLOW_LOGS,
            ItemID.TEAK_LOGS,
            ItemID.MAPLE_LOGS,
            ItemID.MAHOGANY_LOGS,
            ItemID.YEW_LOGS,
            ItemID.MAGIC_LOGS,
            ItemID.REDWOOD_LOGS,
        };
    }

    @Override
    public void startUp()
    {
        if (log.isDebugEnabled())
        {
            log.debug("[birdhouse] [config] equipmentLimit={} logsMax={} regular={} oak={} willow={} teak={} maple={} mahogany={} yew={} magic={} redwood={} blockMessages={}",
                config.birdhouseEquipmentLimitEnabled(), config.birdhouseLogsMax(),
                config.birdhouseLogsRegularEnabled(), config.birdhouseLogsOakEnabled(),
                config.birdhouseLogsWillowEnabled(), config.birdhouseLogsTeakEnabled(),
                config.birdhouseLogsMapleEnabled(), config.birdhouseLogsMahoganyEnabled(),
                config.birdhouseLogsYewEnabled(), config.birdhouseLogsMagicEnabled(),
                config.birdhouseLogsRedwoodEnabled(), config.showBlockMessages());
        }
        eventBus.register(this);
        // Nothing fires ItemContainerChanged until the next inventory change,
        // so starting up already logged in would count nothing carried.
        if (client.getGameState() == GameState.LOGGED_IN)
        {
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

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        trackedCounts.clear();
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
        if (notedToBase == null)
        {
            notedToBase = new HashMap<>();
            for (int id : trackedItems)
            {
                int noted = client.getItemDefinition(id).getLinkedNoteId();
                if (noted != -1)
                {
                    notedToBase.put(noted, id);
                }
            }
        }

        trackedCounts.clear();
        for (Item item : inventory.getItems())
        {
            int id = item.getId();
            if (!trackedItems.contains(id))
            {
                Integer base = notedToBase.get(id);
                if (base == null)
                {
                    continue;
                }
                id = base;
            }
            trackedCounts.merge(id, item.getQuantity(), Integer::sum);
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        // Withdraw options only, so Examine and the other bank item options
        // are left alone.
        if (event.getParam1() != InterfaceID.Bankmain.ITEMS
            || !MenuText.isWithdrawOption(event.getMenuOption()))
        {
            return;
        }

        int itemId = event.getItemId();

        if (config.birdhouseEquipmentLimitEnabled())
        {
            for (EquipmentGroup group : equipmentGroups)
            {
                for (int id : group.itemIds)
                {
                    if (id == itemId)
                    {
                        int current = 0;
                        for (int groupId : group.itemIds)
                        {
                            current += trackedCounts.getOrDefault(groupId, 0);
                        }
                        checkLimit(event, group.itemIds[0], current, group.name, 1);
                        return;
                    }
                }
            }
        }

        switch (itemId)
        {
            case ItemID.LOGS:
                checkLog(event, itemId, "Logs", config.birdhouseLogsRegularEnabled());
                break;
            case ItemID.OAK_LOGS:
                checkLog(event, itemId, "Oak logs", config.birdhouseLogsOakEnabled());
                break;
            case ItemID.WILLOW_LOGS:
                checkLog(event, itemId, "Willow logs", config.birdhouseLogsWillowEnabled());
                break;
            case ItemID.TEAK_LOGS:
                checkLog(event, itemId, "Teak logs", config.birdhouseLogsTeakEnabled());
                break;
            case ItemID.MAPLE_LOGS:
                checkLog(event, itemId, "Maple logs", config.birdhouseLogsMapleEnabled());
                break;
            case ItemID.MAHOGANY_LOGS:
                checkLog(event, itemId, "Mahogany logs", config.birdhouseLogsMahoganyEnabled());
                break;
            case ItemID.YEW_LOGS:
                checkLog(event, itemId, "Yew logs", config.birdhouseLogsYewEnabled());
                break;
            case ItemID.MAGIC_LOGS:
                checkLog(event, itemId, "Magic logs", config.birdhouseLogsMagicEnabled());
                break;
            case ItemID.REDWOOD_LOGS:
                checkLog(event, itemId, "Redwood logs", config.birdhouseLogsRedwoodEnabled());
                break;
            default:
                break;
        }
    }

    private void checkLog(MenuOptionClicked event, int logItemId, String label, boolean enabled)
    {
        if (enabled)
        {
            checkLimit(event, logItemId, trackedCounts.getOrDefault(logItemId, 0), label, config.birdhouseLogsMax());
        }
    }

    private void checkLimit(MenuOptionClicked event, int countedId, int current, String label, int limit)
    {
        if (current >= limit)
        {
            event.consume();
            log.debug("[birdhouse] blocked withdraw of {} option={} current={} limit={}", label, event.getMenuOption(), current, limit);
            if (config.showBlockMessages())
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[WL: Fossil Island] Enough " + label + " already.", null);
            }
            return;
        }

        int remaining = limit - current;
        int requested = MenuText.withdrawAmount(client, event.getItemId(), event.getMenuOption());
        if (requested > remaining)
        {
            event.consume();
            log.debug("[birdhouse] blocked oversized withdraw of {} option={} current={} limit={}", label, event.getMenuOption(), current, limit);
            if (config.showBlockMessages())
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[WL: Fossil Island] Only " + remaining + " more " + label + " allowed.", null);
            }
            return;
        }

        // The Withdraw-X prompt carries no number, so it counts as the full
        // headroom: safer to over-block the next click than let it through.
        if (requested < 0)
        {
            requested = remaining;
        }
        log.debug("[birdhouse] allowed withdraw of {} option={} current={} limit={} counted={}", label, event.getMenuOption(), current, limit, requested);
        trackedCounts.merge(countedId, requested, Integer::sum);
    }
}
