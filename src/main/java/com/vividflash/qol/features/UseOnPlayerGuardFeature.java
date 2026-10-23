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
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ClientTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;

/**
 * Removes "Use [item] -&gt; Player" so an inventory item can't be misclicked
 * onto another player, minus a whitelist of items that legitimately go on one.
 *
 * <p>{@code WIDGET_TARGET_ON_PLAYER} is also the action for casting a spell on
 * a player, so the guard first checks that what is selected is an inventory
 * item. Spell casts on players (Vengeance Other, Heal Other, an offensive cast
 * in the Wilderness) keep their menu entry.</p>
 */
@Slf4j
@Singleton
public class UseOnPlayerGuardFeature implements Feature
{
    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    private Set<String> whitelist = Set.of();
    private boolean enabled;
    // Whitelist verdict for the last selected item, so a held selection is not
    // re-checked on every client tick.
    private int whitelistItemId = -1;
    private boolean whitelistHit;

    @Override
    public void startUp()
    {
        reload();
        eventBus.register(this);
        log.debug("[useplayer] [config] enabled={} whitelist={}", enabled, whitelist.size());
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
            reload();
        }
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        if (!enabled)
        {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN || client.isMenuOpen())
        {
            return;
        }

        int selectedItemId = selectedInventoryItemId();
        if (selectedItemId <= 0)
        {
            // Nothing selected, or a spell rather than an item.
            return;
        }

        if (isWhitelisted(selectedItemId))
        {
            return;
        }

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        int kept = 0;
        for (MenuEntry entry : entries)
        {
            if (entry.getType() != MenuAction.WIDGET_TARGET_ON_PLAYER)
            {
                entries[kept++] = entry;
            }
        }

        if (kept != entries.length)
        {
            menu.setMenuEntries(Arrays.copyOf(entries, kept));
            log.debug("[useplayer] removed {} use-on-player entries for item {}", entries.length - kept, selectedItemId);
        }
    }

    /**
     * Item id of the selected inventory item, or -1 when the selection is a
     * spell or nothing at all. The menu entry itself carries no reliable item
     * id for widget-target actions, so the selection is read from the widget.
     */
    private int selectedInventoryItemId()
    {
        if (!client.isWidgetSelected())
        {
            return -1;
        }
        Widget selected = client.getSelectedWidget();
        if (selected == null || selected.getId() != InterfaceID.Inventory.ITEMS)
        {
            return -1;
        }
        return selected.getItemId();
    }

    private boolean isWhitelisted(int itemId)
    {
        if (whitelist.isEmpty())
        {
            return false;
        }
        if (itemId != whitelistItemId)
        {
            ItemComposition item = client.getItemDefinition(itemId);
            whitelistHit = item != null && whitelist.contains(item.getName().toLowerCase());
            whitelistItemId = itemId;
        }
        return whitelistHit;
    }

    private void reload()
    {
        enabled = config.useItemNoUsePlayer();
        whitelistItemId = -1;
        whitelist = Arrays.stream(config.noUsePlayerWhitelist().split("[,\\r\\n]"))
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .map(String::toLowerCase)
            .collect(Collectors.toCollection(HashSet::new));
    }
}
