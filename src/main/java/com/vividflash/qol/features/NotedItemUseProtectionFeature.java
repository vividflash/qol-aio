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
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.events.ClientTick;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

/**
 * "Noted item Use protection": when a noted item is selected, strips the "Use" option on
 * everything except bank-related targets, so you can't misclick-use a noted
 * item on the wrong thing. Covers the named service NPCs, GE booth/table
 * allowance, a free-text custom list, and treats looting bag/rune pouch notes
 * as eligible trigger items.
 *
 * <p>An object the scene lookup cannot resolve keeps its entry, so an unreadable
 * scene widens what stays clickable instead of narrowing it.</p>
 */
@Slf4j
@Singleton
public class NotedItemUseProtectionFeature implements Feature
{
    private static final Set<MenuAction> BLOCKED_TARGET_ACTIONS = EnumSet.of(
        MenuAction.WIDGET_TARGET_ON_PLAYER,
        MenuAction.WIDGET_TARGET_ON_GROUND_ITEM,
        MenuAction.EXAMINE_OBJECT,
        MenuAction.EXAMINE_NPC,
        MenuAction.EXAMINE_ITEM_GROUND
    );

    private static final String[] BANK_NPC_NAMES = {
        "Banker",
        "Giant Clam",
        "Aisles",
        "Phials",
        "Piles",
        "Tiles",
        "Biles",
        "Isles",
        "Banknote Exchange Merchant",
        "Elder Chaos druid",
        "Virilis",
        "Mercenary",
        "Wesley",
        "Zahur",
        "Friendly Forester",
        "Rick",
        "Maid",
        "Cook",
        "Butler",
        "Demon butler",
    };

    private static final String[] BANK_OBJECT_NAMES = {
        "Bank Chest-wreck",
        "Bank booth",
        "Bank chest",
    };

    private static final String[] BANKNOTE_LIKE_ITEMS = {
        "Looting bag note",
        "Rune pouch note",
    };

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    // Case-insensitive, so menu names are looked up without lower-casing a copy.
    private Set<String> customNames = Set.of();
    private boolean enabled;
    private boolean geBoothsEnabled;
    private boolean tablesEnabled;
    private int lastRemoved;

    // Eligibility of the last selected item, so a held selection does not
    // look up its item definition on every client tick.
    private int eligibleItemId = -1;
    private boolean itemEligible;

    @Override
    public void startUp()
    {
        reload();
        if (log.isDebugEnabled())
        {
            log.debug("[config] noted enabled={} geBooths={} tables={} customNames={}",
                enabled, geBoothsEnabled, tablesEnabled, customNames.size());
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
        if (QolConfig.GROUP.equals(event.getGroup()) && event.getKey().startsWith("easyUnnote"))
        {
            reload();
        }
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        if (!enabled || client.getGameState() != GameState.LOGGED_IN || client.isMenuOpen()
            || !client.isWidgetSelected())
        {
            return;
        }

        Widget selectedWidget = client.getSelectedWidget();
        if (selectedWidget == null || selectedWidget.getId() != InterfaceID.Inventory.ITEMS)
        {
            return;
        }

        int itemId = selectedWidget.getItemId();
        if (itemId <= 0)
        {
            return;
        }

        if (itemId != eligibleItemId)
        {
            itemEligible = isEligibleItem(client.getItemDefinition(itemId));
            eligibleItemId = itemId;
        }
        if (!itemEligible)
        {
            return;
        }

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        int removed = 0;
        for (int i = entries.length - 1; i >= 0; i--)
        {
            if (!isAllowedUseTarget(entries[i]))
            {
                menu.removeMenuEntry(entries[i]);
                removed++;
            }
        }

        if (removed > 0 && removed != lastRemoved)
        {
            if (log.isDebugEnabled())
            {
                log.debug("[noted] stripped {} menu entries for noted item {} ({} kept)", removed, itemId, entries.length - removed);
            }
            lastRemoved = removed;
        }
    }

    private boolean isEligibleItem(ItemComposition item)
    {
        if (item.getNote() != -1)
        {
            return true;
        }
        return containsIgnoreCase(BANKNOTE_LIKE_ITEMS, item.getName());
    }

    private boolean isAllowedUseTarget(MenuEntry entry)
    {
        MenuAction action = entry.getType();
        if (BLOCKED_TARGET_ACTIONS.contains(action))
        {
            return false;
        }

        if (action == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT)
        {
            // An object that cannot be looked up keeps its entry: the guard would otherwise
            // take Use off a bank the tile search does not reach, and there is no
            // message to tell the player why.
            TileObject object = locateTileObject(entry.getParam0(), entry.getParam1(), entry.getIdentifier());
            ObjectComposition composition = object != null ? client.getObjectDefinition(object.getId()) : null;
            return composition == null || isBankLikeObject(composition);
        }

        if (action == MenuAction.WIDGET_TARGET_ON_NPC)
        {
            NPC npc = entry.getNpc();
            if (npc == null)
            {
                return false;
            }
            NPCComposition composition = npc.getTransformedComposition();
            return composition != null && isBankLikeNpc(composition);
        }

        return true;
    }

    /**
     * The scene object a menu entry's coordinates point at, or null when it cannot be
     * addressed. getTiles() is the 104-wide array, while an extended scene runs to 184
     * and a menu entry can belong to a world view whose coordinates do not index this
     * scene at all, so the indices are range-checked before use.
     */
    private TileObject locateTileObject(int sceneX, int sceneY, int objectId)
    {
        WorldView worldView = client.getTopLevelWorldView();
        Scene scene = worldView != null ? worldView.getScene() : null;
        if (scene == null)
        {
            return null;
        }

        Tile[][][] tiles = scene.getTiles();
        int plane = worldView.getPlane();
        if (plane < 0 || plane >= tiles.length
            || sceneX < 0 || sceneX >= tiles[plane].length
            || sceneY < 0 || sceneY >= tiles[plane][sceneX].length)
        {
            return null;
        }

        Tile tile = tiles[plane][sceneX][sceneY];
        if (tile == null)
        {
            return null;
        }

        TileObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null)
        {
            for (TileObject candidate : gameObjects)
            {
                if (candidate != null && candidate.getId() == objectId)
                {
                    return candidate;
                }
            }
        }
        // Decorative and ground objects are searched too: some bank furniture is not a
        // game object or a wall object.
        if (hasId(tile.getWallObject(), objectId))
        {
            return tile.getWallObject();
        }
        if (hasId(tile.getDecorativeObject(), objectId))
        {
            return tile.getDecorativeObject();
        }
        return hasId(tile.getGroundObject(), objectId) ? tile.getGroundObject() : null;
    }

    private static boolean hasId(TileObject object, int objectId)
    {
        return object != null && object.getId() == objectId;
    }

    private boolean matchesNameOrBankAction(String name, String[] actions, String[] knownNames)
    {
        return containsIgnoreCase(knownNames, name) || hasBankAction(actions);
    }

    private boolean hasBankAction(String[] actions)
    {
        return actions != null && containsIgnoreCase(actions, "Bank");
    }

    private static boolean containsIgnoreCase(String[] values, String name)
    {
        for (String value : values)
        {
            if (name.equalsIgnoreCase(value))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The toggles and the custom allow-list, read once per config change: they
     * are used for every menu entry on every tick, which is no place to call the
     * config proxy or re-split a string.
     */
    private void reload()
    {
        enabled = config.easyUnnote();
        geBoothsEnabled = config.easyUnnoteEnableGeBooths();
        tablesEnabled = config.easyUnnoteEnableTables();

        // Users paste either a comma list or one name per line, so fold the
        // newlines into commas and let fromCSV do the trimming.
        String configured = config.easyUnnoteCustomList().replace('\n', ',').replace('\r', ',');
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(Text.fromCSV(configured));
        customNames = names;
    }

    private boolean isBankLikeNpc(NPCComposition npc)
    {
        if (customNames.contains(npc.getName()))
        {
            return true;
        }
        return matchesNameOrBankAction(npc.getName(), npc.getActions(), BANK_NPC_NAMES);
    }

    private boolean isBankLikeObject(ObjectComposition object)
    {
        ObjectComposition resolved = resolveImpostor(object);
        String name = resolved.getName();
        if (name == null)
        {
            return false;
        }

        boolean known = containsIgnoreCase(BANK_OBJECT_NAMES, name) || customNames.contains(name);
        if (known)
        {
            return true;
        }

        Boolean toggled = toggledFamily(name);
        return toggled != null ? toggled : hasBankAction(resolved.getActions());
    }

    /**
     * GE booths and bank tables/counters answer to their own toggle and nothing
     * else: with the toggle off they stay blocked even though most of them do
     * carry a "Bank" action. Null means the object is in neither family.
     */
    private Boolean toggledFamily(String name)
    {
        if ("Grand Exchange booth".equalsIgnoreCase(name))
        {
            return geBoothsEnabled;
        }
        if (name.regionMatches(true, name.length() - 5, "table", 0, 5) || "Counter".equals(name))
        {
            return tablesEnabled;
        }
        return null;
    }

    /** Objects that swap form by varbit (open/closed booths, etc.). */
    private ObjectComposition resolveImpostor(ObjectComposition object)
    {
        if (object.getImpostorIds() == null)
        {
            return object;
        }
        ObjectComposition impostor = object.getImpostor();
        return impostor != null ? impostor : object;
    }
}
