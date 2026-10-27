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
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

// "Take before Attack": near configured named monsters, moves the "Attack" NPC option to the bottom
// of the menu, below "Walk here", so a "Take" on the drop wins the left-click and you loot before
// re-engaging. Runs after RuneLite's own sort (priority -1).
//
// Scoping: the monster list enables the swap; the monster exclusion list disables it again
// (exclusion wins if a monster matches both). The ground-item exclusion list independently
// withholds specific item drops (e.g. plain Bones) from being prioritized, regardless of the
// monster.
@Slf4j
@Singleton
public class TakeBeforeAttackFeature implements Feature
{
    private boolean attackMoved;
    private boolean enabled;

    private List<Pattern> monsterPatterns = List.of();
    private List<Pattern> monsterExcludePatterns = List.of();
    private List<Pattern> itemExcludePatterns = List.of();

    // Last raw target and its verdict per kind, so a hovered drop or monster
    // is not re-matched on every menu build.
    private String lastTakeTarget;
    private boolean lastTakeVerdict;
    private String lastAttackTarget;
    private boolean lastAttackVerdict;

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
            log.debug("[takefirst] [config] enabled={} monsters={} monsterExcludes={} itemExcludes={}",
                enabled, monsterPatterns.size(), monsterExcludePatterns.size(), itemExcludePatterns.size());
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
        if (QolConfig.GROUP.equals(event.getGroup()) && event.getKey().startsWith("takeBeforeAttack"))
        {
            reload();
        }
    }

    @Subscribe(priority = -1)
    public void onPostMenuSort(PostMenuSort event)
    {
        if (!enabled || monsterPatterns.isEmpty())
        {
            return;
        }

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();

        // Only bother if a Take entry and a matching Attack entry both exist.
        // Take is looked for first: without a drop on the ground the monster
        // names never need reading.
        boolean hasTake = false;
        for (MenuEntry entry : entries)
        {
            if (isTake(entry))
            {
                hasTake = true;
                break;
            }
        }
        if (!hasTake)
        {
            attackMoved = false;
            return;
        }

        // Menu entries render bottom-up (last = top). Moving Attack entries to
        // the very front of the array puts them at the bottom of the menu,
        // below "Walk here", so Take outranks them. One in-place pass keeps
        // the order within both groups.
        int attackSlot = 0;
        for (int i = 0; i < entries.length; i++)
        {
            MenuEntry entry = entries[i];
            if (isTargetAttack(entry))
            {
                System.arraycopy(entries, attackSlot, entries, attackSlot + 1, i - attackSlot);
                entries[attackSlot++] = entry;
            }
        }
        int attackCount = attackSlot;
        if (attackCount == 0)
        {
            attackMoved = false;
            return;
        }
        if (!attackMoved)
        {
            attackMoved = true;
            log.debug("[takefirst] moved {} Attack entries below Walk here, menuSize={}", attackCount, entries.length);
        }
        menu.setMenuEntries(entries);
    }

    private boolean isTargetAttack(MenuEntry entry)
    {
        if (entry.getType() != MenuAction.NPC_FIRST_OPTION
            && entry.getType() != MenuAction.NPC_SECOND_OPTION)
        {
            return false;
        }
        if (!"attack".equalsIgnoreCase(untagged(entry.getOption())))
        {
            return false;
        }
        String raw = entry.getTarget();
        if (!raw.equals(lastAttackTarget))
        {
            String target = MenuText.stripCombatLevel(Text.removeTags(raw).toLowerCase(Locale.ROOT));
            // The exclusion list wins: a monster matching both the enable list and
            // the exclude list is treated as not-a-target (swap does not apply).
            lastAttackVerdict = !matchesAny(monsterExcludePatterns, target) && matchesAny(monsterPatterns, target);
            lastAttackTarget = raw;
        }
        return lastAttackVerdict;
    }

    private boolean isTake(MenuEntry entry)
    {
        MenuAction type = entry.getType();
        boolean groundItem = type == MenuAction.GROUND_ITEM_FIRST_OPTION
            || type == MenuAction.GROUND_ITEM_SECOND_OPTION
            || type == MenuAction.GROUND_ITEM_THIRD_OPTION
            || type == MenuAction.GROUND_ITEM_FOURTH_OPTION
            || type == MenuAction.GROUND_ITEM_FIFTH_OPTION;
        if (!groundItem || !"take".equalsIgnoreCase(untagged(entry.getOption())))
        {
            return false;
        }
        // Ground-item exclusions withhold specific drops (e.g. plain Bones)
        // from being prioritized, independent of the monster scoping above.
        String raw = entry.getTarget();
        if (!raw.equals(lastTakeTarget))
        {
            String itemName = Text.removeTags(raw).toLowerCase(Locale.ROOT);
            lastTakeVerdict = !matchesAny(itemExcludePatterns, itemName);
            lastTakeTarget = raw;
        }
        return lastTakeVerdict;
    }

    // Options rarely carry tags, so skip the copy when there are none.
    private static String untagged(String option)
    {
        return option.indexOf('<') >= 0 ? Text.removeTags(option) : option;
    }

    private boolean matchesAny(List<Pattern> patterns, String value)
    {
        return MenuText.matchesAny(patterns, value);
    }

    private void reload()
    {
        lastTakeTarget = null;
        lastAttackTarget = null;
        enabled = config.takeBeforeAttack();
        monsterPatterns = MenuText.globList(config.takeBeforeAttackMonsters());
        monsterExcludePatterns = MenuText.globList(config.takeBeforeAttackMonstersExclude());
        itemExcludePatterns = MenuText.globList(config.takeBeforeAttackItemExclude());
        if (log.isDebugEnabled())
        {
            log.debug("[takefirst] lists loaded monsters={} monsterExcludes={} itemExcludes={}",
                monsterPatterns.size(), monsterExcludePatterns.size(), itemExcludePatterns.size());
        }
    }
}
