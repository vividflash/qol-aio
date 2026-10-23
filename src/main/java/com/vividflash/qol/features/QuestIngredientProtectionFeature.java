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
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;

/**
 * Removes the "Eat"/"Drink" option on quest items that are easy to
 * misclick-consume and lose (they're needed for a quest step, not for food
 * value).
 */
@Slf4j
@Singleton
public class QuestIngredientProtectionFeature implements Feature
{
    private static final Set<Integer> QUEST_FOOD_ITEMS = Set.of(
        ItemID.TOMATO,
        ItemID.BANANA,
        ItemID.CHOCOLATE_BAR,
        ItemID.CHOCOLATY_MILK,
        ItemID.CHEESE,
        ItemID.ORANGE_SLICES,
        ItemID.HUNDRED_PIRATE_FISHCAKE,
        ItemID._100_JUBBLY_MEAT_COOKED,
        ItemID.HUNDRED_ILM_COOKED_STUFFED_SNAKE,
        ItemID.VILLAGE_RARE_TUBER,
        ItemID.ONION,
        ItemID.CABBAGE,
        // Pirate's Treasure (smuggled hand-in; also Tai Bwo Wannai Trio)
        ItemID.KARAMJA_RUM,
        // The Knight's Sword, The Giant Dwarf, Prying Times
        ItemID.REDBERRY_PIE,
        // Mourning's End Part I (gnome bribe; premade is the bought variant)
        ItemID.TOAD_CRUNCHIES,
        ItemID.PREMADE_TOAD_CRUNCHIES,
        // Plague City (gas mask), The Curse of Arrav
        ItemID.DWELLBERRIES,
        // Watchtower (ogre potion), Fairytale I
        ItemID.JANGERBERRIES,
        // Ghosts Ahoy (old crone's tea, all bowl/cup/porcelain variants)
        ItemID.BOWL_NETTLETEA,
        ItemID.BOWL_NETTLETEA_MILKY,
        ItemID.CUP_OF_NETTLETEA,
        ItemID.CUP_OF_NETTLETEA_MILKY,
        ItemID.CHINACUP_OF_NETTLETEA,
        ItemID.CHINACUP_OF_NETTLETEA_MILKY
    );

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    // Whether the inventory holds a guarded quest item, kept from inventory
    // changes so the per-tick filter skips the menu read when there is none.
    private boolean questFoodCarried;
    private volatile boolean inventoryLoaded;
    private boolean consumeHidden;
    private volatile boolean enabled;

    @Override
    public void startUp()
    {
        enabled = config.questFoodGuard();
        log.debug("[questfood] [config] enabled={} guardedItems={}", enabled, QUEST_FOOD_ITEMS.size());
        inventoryLoaded = false;
        eventBus.register(this);
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (QolConfig.GROUP.equals(event.getGroup()))
        {
            boolean wasEnabled = enabled;
            enabled = config.questFoodGuard();
            if (enabled && !wasEnabled)
            {
                // Inventory changes were skipped while off; onClientTick reloads.
                inventoryLoaded = false;
            }
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (enabled && event.getContainerId() == InventoryID.INV)
        {
            loadInventory(event.getItemContainer());
        }
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        if (!enabled || (inventoryLoaded && !questFoodCarried))
        {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN || client.isMenuOpen())
        {
            return;
        }

        if (!inventoryLoaded)
        {
            loadInventory(client.getItemContainer(InventoryID.INV));
        }
        if (!questFoodCarried)
        {
            return;
        }

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        int removedCount = 0;
        for (int i = entries.length - 1; i >= 0; i--)
        {
            MenuEntry entry = entries[i];
            if (isConsumeOption(entry.getOption()) && QUEST_FOOD_ITEMS.contains(entry.getItemId()))
            {
                menu.removeMenuEntry(entry);
                removedCount++;
            }
        }

        boolean removed = removedCount > 0;
        if (removed != consumeHidden)
        {
            consumeHidden = removed;
            log.debug("[questfood] eat/drink option hidden={} removedEntries={}", removed, removedCount);
        }
    }

    private void loadInventory(ItemContainer inventory)
    {
        inventoryLoaded = true;
        questFoodCarried = false;
        if (inventory == null)
        {
            return;
        }
        for (Item item : inventory.getItems())
        {
            if (QUEST_FOOD_ITEMS.contains(item.getId()))
            {
                questFoodCarried = true;
                return;
            }
        }
    }

    private static boolean isConsumeOption(String option)
    {
        return "Eat".equalsIgnoreCase(option) || "Drink".equalsIgnoreCase(option);
    }
}
