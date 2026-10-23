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
import java.util.regex.Pattern;
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
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

/**
 * "No Empty Potion": removes the "Empty" option on potions, at every dose
 * count including full ones (item name ending "(N)"), so you can't misclick
 * and waste doses. The dose suffix is what identifies a potion here, so no
 * per-item name list is needed: barbarian mixes, brews, serums and overloads
 * all carry it, and the non-potion containers with an "Empty" option (buckets,
 * vials, sacks, cups) do not.
 */
@Slf4j
@Singleton
public class NoEmptyPotionFeature implements Feature
{
    private static final Pattern DOSE_PATTERN = Pattern.compile(".*\\(\\d\\)");

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    // Whether the inventory holds a dosed potion, kept from inventory changes
    // so the per-tick filter skips the menu read when there is none.
    private boolean potionCarried;
    private volatile boolean inventoryLoaded;
    private volatile boolean enabled;

    @Override
    public void startUp()
    {
        inventoryLoaded = false;
        enabled = config.noEmptyPotion();
        eventBus.register(this);
        log.debug("[potion] [config] enabled={}", enabled);
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
            enabled = config.noEmptyPotion();
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
        if (!enabled || (inventoryLoaded && !potionCarried))
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
        if (!potionCarried)
        {
            return;
        }

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        for (int i = entries.length - 1; i >= 0; i--)
        {
            if (isEmptyPotionOption(entries[i]))
            {
                menu.removeMenuEntry(entries[i]);
            }
        }
    }

    private void loadInventory(ItemContainer inventory)
    {
        inventoryLoaded = true;
        boolean wasCarried = potionCarried;
        potionCarried = false;
        if (inventory == null)
        {
            return;
        }
        for (Item item : inventory.getItems())
        {
            if (item.getId() > 0
                && DOSE_PATTERN.matcher(client.getItemDefinition(item.getId()).getName()).matches())
            {
                potionCarried = true;
                if (!wasCarried)
                {
                    log.debug("[potion] dosed potion now in inventory, Empty option will be removed");
                }
                return;
            }
        }
        if (wasCarried)
        {
            log.debug("[potion] no dosed potion left in inventory, filter idle");
        }
    }

    private boolean isEmptyPotionOption(MenuEntry entry)
    {
        if (!"empty".equalsIgnoreCase(entry.getOption()))
        {
            return false;
        }
        String target = Text.removeTags(entry.getTarget());
        return DOSE_PATTERN.matcher(target).matches();
    }
}
