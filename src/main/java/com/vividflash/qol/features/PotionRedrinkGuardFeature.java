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
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.Skill;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

/**
 * "Potion re-drink guard": removes the "Drink" option on a stat-boost potion while
 * the skill it boosts is still comfortably above base level, so you don't waste
 * a dose re-potting too early. Name-keyed so no potion item IDs are hardcoded.
 */
@Slf4j
@Singleton
public class PotionRedrinkGuardFeature implements Feature
{
    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    // Whether the inventory holds a guarded potion, kept from inventory
    // changes so the per-tick filter skips the menu read when there is none.
    private boolean potionCarried;
    private boolean inventoryLoaded;
    private boolean drinkHidden;
    private boolean enabled;
    private int threshold;
    // Last Drink target and the skill it maps to, so a hovered potion is not
    // re-classified on every client tick.
    private String lastDrinkTarget;
    private Skill lastDrinkSkill;

    @Override
    public void startUp()
    {
        loadConfig();
        log.debug("[redrink] [config] enabled={} threshold={}", enabled, threshold);
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
            loadConfig();
        }
    }

    private void loadConfig()
    {
        enabled = config.noMisclickRepot();
        threshold = config.noMisclickRepotThreshold();
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() == InventoryID.INV)
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
        int kept = 0;
        for (MenuEntry entry : entries)
        {
            if (!shouldRemove(entry))
            {
                entries[kept++] = entry;
            }
        }

        boolean removed = kept != entries.length;
        if (removed != drinkHidden)
        {
            drinkHidden = removed;
            log.debug("[redrink] drink option hidden={} removedEntries={}", removed, entries.length - kept);
        }
        if (removed)
        {
            menu.setMenuEntries(Arrays.copyOf(entries, kept));
        }
    }

    private void loadInventory(ItemContainer inventory)
    {
        inventoryLoaded = true;
        potionCarried = false;
        if (inventory == null)
        {
            return;
        }
        for (Item item : inventory.getItems())
        {
            if (item.getId() > 0
                && boostedSkill(client.getItemDefinition(item.getId()).getName().toLowerCase(Locale.ROOT)) != null)
            {
                potionCarried = true;
                return;
            }
        }
    }

    private boolean shouldRemove(MenuEntry entry)
    {
        String option = entry.getOption();
        if (option.indexOf('<') >= 0)
        {
            option = Text.removeTags(option);
        }
        if (!"drink".equalsIgnoreCase(option))
        {
            return false;
        }

        String target = entry.getTarget();
        if (!target.equals(lastDrinkTarget))
        {
            lastDrinkTarget = target;
            lastDrinkSkill = boostedSkill(Text.removeTags(target).toLowerCase(Locale.ROOT));
        }
        Skill skill = lastDrinkSkill;
        if (skill == null)
        {
            return false;
        }

        int boosted = client.getBoostedSkillLevel(skill);
        int real = client.getRealSkillLevel(skill);
        // Still boosted by at least the margin -> re-potting now is wasteful.
        return boosted - real >= threshold;
    }

    /**
     * Maps a potion name to the single skill it boosts, for the potions where a
     * single-skill boost makes early re-potting a clear waste. Combo potions that
     * boost several skills are intentionally left out (ambiguous).
     */
    private Skill boostedSkill(String name)
    {
        if (name.contains("ranging potion") || name.contains("bastion") || name.contains("divine ranging")
            || name.contains("divine bastion"))
        {
            return Skill.RANGED;
        }
        // The imbued heart is not listed: its option is Invigorate, and only
        // Drink reaches this method.
        if (name.contains("magic potion") || name.contains("battlemage") || name.contains("divine magic")
            || name.contains("divine battlemage"))
        {
            return Skill.MAGIC;
        }
        if (name.contains("prayer potion") || name.contains("prayer enhance") || name.contains("sanfew")
            || name.contains("super restore"))
        {
            // These restore rather than boost; skip to avoid false blocks.
            return null;
        }
        if (name.contains("stamina potion") || name.contains("energy potion"))
        {
            return null;
        }
        if (name.contains("super strength") || name.contains("strength potion"))
        {
            return Skill.STRENGTH;
        }
        if (name.contains("super attack") || name.contains("attack potion"))
        {
            return Skill.ATTACK;
        }
        if (name.contains("super defence") || name.contains("defence potion"))
        {
            return Skill.DEFENCE;
        }
        if (name.contains("hunter potion"))
        {
            return Skill.HUNTER;
        }
        if (name.contains("fishing potion"))
        {
            return Skill.FISHING;
        }
        return null;
    }
}
