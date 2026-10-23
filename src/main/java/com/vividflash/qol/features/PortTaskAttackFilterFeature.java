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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

/**
 * Sailing: removes the "Attack" option on sea bounty monsters that none of
 * the five port-task slots currently targets, so a misclick can't start a
 * fight whose kill counts for nothing.
 * <p>
 * Everything here is scoped to being aboard: a sailing boat is a
 * {@link net.runelite.api.WorldEntity} with its own non-top-level
 * {@link WorldView}, so a player standing on one reads as outside the
 * top-level view. Ashore, the DB table is never read and no menu is ever
 * touched.
 * <p>
 * The game's PortTask DB table (cached on the first boarding after startup;
 * it is game cache data and does not change) maps each task row
 * to its bounty-target NPC ids; the five task-slot varbits hold the active row
 * ids (0 = empty slot). Only NPC ids that appear as a bounty target somewhere
 * in the table are ever filtered, which self-scopes the guard to sea bounty
 * monsters, so Attack is untouched everywhere else. Fail-open: no cached
 * table, an unknown row id, or an empty target set means nothing is filtered.
 */
@Slf4j
@Singleton
public class PortTaskAttackFilterFeature implements Feature
{
    private static final int[] TASK_SLOT_VARBITS = {
        VarbitID.PORT_TASK_SLOT_0_ID,
        VarbitID.PORT_TASK_SLOT_1_ID,
        VarbitID.PORT_TASK_SLOT_2_ID,
        VarbitID.PORT_TASK_SLOT_3_ID,
        VarbitID.PORT_TASK_SLOT_4_ID
    };

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    // PortTask row id -> that task's bounty-target NPC ids (alive and dead
    // forms; courier rows have no target and are skipped).
    private final Map<Integer, Set<Integer>> targetsByTaskId = new HashMap<>();
    // Every bounty-target NPC id in the table; scopes the filter.
    private final Set<Integer> allTargetNpcIds = new HashSet<>();
    // Targets of the currently held tasks.
    private final Set<Integer> activeTargetNpcIds = new HashSet<>();
    private boolean tableCached;
    // Set on logout or hop, so the next account or world reads its own task
    // slots before anything is filtered.
    private boolean targetsStale;
    private int lastRemoved;
    private boolean enabled;

    @Override
    public void startUp()
    {
        enabled = config.sailingAttackFilter();
        log.debug("[config] porttask enabled={}", enabled);
        eventBus.register(this);
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        tableCached = false;
        targetsStale = false;
        targetsByTaskId.clear();
        allTargetNpcIds.clear();
        activeTargetNpcIds.clear();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (QolConfig.GROUP.equals(event.getGroup()))
        {
            enabled = config.sailingAttackFilter();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();
        if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
        {
            activeTargetNpcIds.clear();
            targetsStale = true;
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if ((!tableCached || targetsStale) && enabled
            && client.getGameState() == GameState.LOGGED_IN && onBoat())
        {
            if (!tableCached)
            {
                cacheTable();
            }
            refreshActiveTargets();
            targetsStale = false;
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        for (int varbit : TASK_SLOT_VARBITS)
        {
            if (event.getVarbitId() == varbit)
            {
                refreshActiveTargets();
                return;
            }
        }
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        // Fail open until the table is read and the task slots are current.
        if (!enabled || !tableCached || targetsStale || allTargetNpcIds.isEmpty())
        {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN || client.isMenuOpen())
        {
            return;
        }

        if (!onBoat())
        {
            return;
        }

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        int kept = 0;
        for (MenuEntry entry : entries)
        {
            if (!isUntargetedBountyAttack(entry))
            {
                entries[kept++] = entry;
            }
        }

        if (kept != entries.length)
        {
            menu.setMenuEntries(Arrays.copyOf(entries, kept));
            int removed = entries.length - kept;
            if (removed != lastRemoved)
            {
                log.debug("[porttask] removed {} Attack entries on untargeted bounty monsters", removed);
                lastRemoved = removed;
            }
        }
    }

    /**
     * True while the player is standing on a boat. Fails closed: no local
     * player or no world view means the guard stays inactive.
     */
    private boolean onBoat()
    {
        Player local = client.getLocalPlayer();
        if (local == null)
        {
            return false;
        }

        WorldView worldView = local.getWorldView();
        return worldView != null && !worldView.isTopLevel();
    }

    private void cacheTable()
    {
        targetsByTaskId.clear();
        allTargetNpcIds.clear();

        List<Integer> rows = client.getDBTableRows(DBTableID.PortTask.ID);
        if (rows == null)
        {
            log.debug("[porttask] PortTask DB table rows unavailable, nothing will be filtered");
            return;
        }

        for (int row : rows)
        {
            // Key by the row's own task id, not the row handle: the slot varbits
            // hold a COL_TASK_ID value, and the two only coincide by accident.
            Integer taskId = firstIntValue(row, DBTableID.PortTask.COL_TASK_ID);
            if (taskId == null)
            {
                continue;
            }

            Set<Integer> targets = new HashSet<>();
            addColumnValues(targets, row, DBTableID.PortTask.COL_BOUNTY_TARGET_ALIVE);
            addColumnValues(targets, row, DBTableID.PortTask.COL_BOUNTY_TARGET_DEAD);
            if (!targets.isEmpty())
            {
                targetsByTaskId.put(taskId, targets);
                allTargetNpcIds.addAll(targets);
            }
        }
        // An empty read is retried on the next boat tick.
        tableCached = !allTargetNpcIds.isEmpty();
        log.debug("[porttask] table cached: {} tasks with targets, {} target npc ids", targetsByTaskId.size(), allTargetNpcIds.size());
    }

    /** First integer in a column, or null when the column is missing or not an int. */
    private Integer firstIntValue(int row, int column)
    {
        Object[] values = client.getDBTableField(row, column, 0);
        if (values == null)
        {
            return null;
        }
        for (Object value : values)
        {
            if (value instanceof Integer)
            {
                return (Integer) value;
            }
        }
        return null;
    }

    private void addColumnValues(Set<Integer> out, int row, int column)
    {
        Object[] values = client.getDBTableField(row, column, 0);
        if (values == null)
        {
            return;
        }
        for (Object value : values)
        {
            if (value instanceof Integer)
            {
                out.add((Integer) value);
            }
        }
    }

    private void refreshActiveTargets()
    {
        activeTargetNpcIds.clear();
        for (int varbit : TASK_SLOT_VARBITS)
        {
            int taskId = client.getVarbitValue(varbit);
            if (taskId <= 0)
            {
                continue;
            }
            Set<Integer> targets = targetsByTaskId.get(taskId);
            if (targets != null)
            {
                activeTargetNpcIds.addAll(targets);
            }
        }
        log.debug("[porttask] active task targets refreshed: {} npc ids", activeTargetNpcIds.size());
    }

    private boolean isUntargetedBountyAttack(MenuEntry entry)
    {
        if (!isNpcOption(entry) || !"attack".equalsIgnoreCase(untagged(entry.getOption())))
        {
            return false;
        }

        NPC npc = entry.getNpc();
        if (npc == null)
        {
            return false;
        }

        int npcId = npc.getId();
        return allTargetNpcIds.contains(npcId) && !activeTargetNpcIds.contains(npcId);
    }

    /** Options rarely carry tags, so skip the copy when there are none. */
    private static String untagged(String option)
    {
        return option.indexOf('<') >= 0 ? Text.removeTags(option) : option;
    }

    private static boolean isNpcOption(MenuEntry entry)
    {
        MenuAction type = entry.getType();
        return type == MenuAction.NPC_FIRST_OPTION
            || type == MenuAction.NPC_SECOND_OPTION
            || type == MenuAction.NPC_THIRD_OPTION
            || type == MenuAction.NPC_FOURTH_OPTION
            || type == MenuAction.NPC_FIFTH_OPTION;
    }
}
