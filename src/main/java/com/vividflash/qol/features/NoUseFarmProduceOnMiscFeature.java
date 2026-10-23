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
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.client.util.Text;

/**
 * Removes "Use [farm produce] -&gt; farming patch / stone" menu entries.
 * The Ardougne tool leprechaun stands on a stone, so at some camera angles
 * the note-your-produce click lands on the stone (or the patch beside him)
 * instead of the leprechaun; the misclick just walks you over and fizzles,
 * so nothing is lost, but removing the entries stops the pointless walk and
 * cuts menu clutter. Menu-removal style: silent, no chat feedback.
 *
 * <p>Saplings get their own rule: they are the one produce-shaped item that IS
 * legitimately Use'd on a patch (planting), so the patch/stone rule exempts
 * them, and instead "&lt;x&gt; sapling" is blocked on decorative scenery from the
 * configurable list. The default is the Daisies beside Lletya's fruit tree
 * patch, which catch the planting click at some camera angles.</p>
 *
 * <p>Detection is name-based (produce list config + object-name keywords) so it
 * needs no hardcoded patch object IDs, which vary per patch across the game.</p>
 */
@Slf4j
@Singleton
public class NoUseFarmProduceOnMiscFeature implements Feature
{
    private boolean entriesHidden;

    // Object-name keywords that indicate a farming patch or the leprechaun's stone.
    private static final String[] OBJECT_KEYWORDS = {
        "patch", "stone", "allotment", "herb", "flower", "hops", "bush",
        "tree", "fruit tree", "vine", "cactus",
    };

    // Saplings are legitimately Use'd on patches (planting), so the keyword
    // rule must never catch them ("Apple*" in the produce list would otherwise
    // block "Apple sapling -> Fruit tree patch"). They get the scenery rule.
    private static final Pattern SAPLING_PATTERN = Pattern.compile("^.* sapling$");

    private List<Pattern> producePatterns = List.of();
    private List<Pattern> sceneryPatterns = List.of();
    private boolean enabled;

    private static final int RULE_NONE = 0;
    private static final int RULE_PRODUCE = 1;
    private static final int RULE_SAPLING = 2;

    // Rule for the last selected item, so a held selection is not
    // re-classified on every client tick.
    private int ruleItemId = -1;
    private int itemRule;

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    @Override
    public void startUp()
    {
        reload();
        if (log.isDebugEnabled())
        {
            log.debug("[farmuse] [config] enabled={} produceRules={} sceneryRules={}",
                enabled, producePatterns.size(), sceneryPatterns.size());
        }
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
        if (QolConfig.GROUP.equals(event.getGroup())
            && (event.getKey().startsWith("noUsePlantsOnStone") || "noUseSaplingsOnSceneryList".equals(event.getKey())))
        {
            reload();
        }
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        if (!enabled
            || (producePatterns.isEmpty() && sceneryPatterns.isEmpty()))
        {
            return;
        }

        // "Use X -> object" entries only exist while an item is selected.
        if (client.getGameState() != GameState.LOGGED_IN || client.isMenuOpen() || !client.isWidgetSelected())
        {
            return;
        }

        // The produce half of every "Use X -> object" entry is the selected
        // item, so it is classified once per selection, not once per entry.
        int rule = selectedItemRule();
        if (rule == RULE_NONE)
        {
            return;
        }

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        int kept = 0;
        for (MenuEntry entry : entries)
        {
            if (!isBlockedTarget(entry, rule))
            {
                entries[kept++] = entry;
            }
        }

        boolean removed = kept != entries.length;
        if (removed != entriesHidden)
        {
            entriesHidden = removed;
            log.debug("[farmuse] use-on-patch entries hidden={} removedEntries={}", removed, entries.length - kept);
        }
        if (removed)
        {
            menu.setMenuEntries(Arrays.copyOf(entries, kept));
        }
    }

    /**
     * Which rule the selected inventory item falls under. A spell or any other
     * non-inventory selection matches no rule, so its entries stay.
     */
    private int selectedItemRule()
    {
        Widget selected = client.getSelectedWidget();
        if (selected == null || selected.getId() != InterfaceID.Inventory.ITEMS)
        {
            return RULE_NONE;
        }
        int itemId = selected.getItemId();
        if (itemId <= 0)
        {
            return RULE_NONE;
        }
        if (itemId != ruleItemId)
        {
            String name = client.getItemDefinition(itemId).getName().toLowerCase(Locale.ROOT);
            if (SAPLING_PATTERN.matcher(name).matches())
            {
                itemRule = sceneryPatterns.isEmpty() ? RULE_NONE : RULE_SAPLING;
            }
            else
            {
                itemRule = MenuText.matchesAny(producePatterns, name) ? RULE_PRODUCE : RULE_NONE;
            }
            ruleItemId = itemId;
        }
        return itemRule;
    }

    private boolean isBlockedTarget(MenuEntry entry, int rule)
    {
        if (entry.getType() != MenuAction.WIDGET_TARGET_ON_GAME_OBJECT)
        {
            return false;
        }

        // Target reads like "<produce> -> <object>"; only the object half is read here.
        String target = Text.removeTags(entry.getTarget()).toLowerCase(Locale.ROOT);
        int arrow = target.indexOf("->");
        String objectPart = arrow >= 0 ? target.substring(arrow + 2).trim() : target;

        if (rule == RULE_SAPLING)
        {
            return MenuText.matchesAny(sceneryPatterns, objectPart);
        }

        for (String kw : OBJECT_KEYWORDS)
        {
            if (objectPart.contains(kw))
            {
                return true;
            }
        }
        return false;
    }

    private void reload()
    {
        ruleItemId = -1;
        enabled = config.noUsePlantsOnStone();
        producePatterns = MenuText.globList(config.noUsePlantsOnStoneList());
        sceneryPatterns = MenuText.globList(config.noUseSaplingsOnSceneryList());
    }
}
