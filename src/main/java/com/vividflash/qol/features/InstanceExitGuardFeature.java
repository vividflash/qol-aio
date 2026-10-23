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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Instance exit guard: while the player is inside an instance with valuable loot
 * still on the floor, sinks the options that would take them out of it (object
 * options like Exit/Leave/Travel, plus the inventory/spell options that are just
 * as easy to misclick in that moment, such as Break or a Nardah teleport) below
 * "Walk here". Nothing is removed, so the option stays one right-click away; it
 * just cannot take the left-click while the drop is still on the ground.
 *
 * <p>Valuable is either a whitelisted item name or a stack whose price times
 * its quantity reaches the configured threshold. Ground items are tracked from
 * spawn/despawn/quantity events rather than re-scanned, inside any instance
 * (also with the guard off), and the whole record is dropped on every load screen.
 */
@Slf4j
@Singleton
public class InstanceExitGuardFeature implements Feature
{
    private static final Set<MenuAction> OBJECT_OPTIONS = Set.of(
        MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION,
        MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
        MenuAction.GAME_OBJECT_FIFTH_OPTION
    );

    // Inventory, equipment and spellbook left/right click options all arrive as
    // one of these two widget actions.
    private static final Set<MenuAction> WIDGET_OPTIONS = Set.of(
        MenuAction.CC_OP, MenuAction.CC_OP_LOW_PRIORITY
    );

    private static final String USE_OPTION = "Use";

    // Any always-traded item: a price above 0 means the price map has loaded.
    private static final int PRICE_PROBE_ITEM = ItemID.FIRERUNE;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager itemManager;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    /** Ground items by tile, then by item id, holding the stacked quantity. */
    private final Map<WorldPoint, Map<Integer, Integer>> groundItems = new HashMap<>();

    // Cached answer of valuableLootAround(), recomputed only after the tracked
    // stacks or the settings change instead of on every menu build.
    private boolean valuableLoot;
    private boolean valuableLootDirty = true;
    // Sunk count last logged, so the per-frame reorder logs only on change.
    private int lastSunk;
    // ItemManager fills its price map on an executor after client start and
    // posts no event, so a scan before that saw every price as 0. Latched once
    // a known tradeable reads a price; the scan is then dirtied one more time.
    private boolean pricesLoaded;

    private boolean enabled;
    private int lootValue;
    private List<Pattern> whitelist = List.of();
    private List<Pattern> objectOptions = List.of();
    private List<Pattern> itemOptions = List.of();

    @Override
    public void startUp()
    {
        reload();
        eventBus.register(this);
        if (log.isDebugEnabled())
        {
            log.debug("[exit] [config] enabled={} lootValue={} whitelist={} objectOptions={} itemOptions={}",
                enabled, lootValue, whitelist.size(), objectOptions.size(), itemOptions.size());
        }
        // Spawn events for loot already on the floor have passed, so starting
        // up inside an instance reads the current scene once.
        clientThread.invoke(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                primeGroundItems();
            }
        });
    }

    private void primeGroundItems()
    {
        WorldView worldView = client.getTopLevelWorldView();
        Scene scene = worldView != null ? worldView.getScene() : null;
        if (scene == null || !worldView.isInstance())
        {
            return;
        }
        groundItems.clear();
        for (Tile[][] plane : scene.getTiles())
        {
            for (Tile[] column : plane)
            {
                for (Tile tile : column)
                {
                    List<TileItem> items = tile != null ? tile.getGroundItems() : null;
                    if (items == null || items.isEmpty())
                    {
                        continue;
                    }
                    Map<Integer, Integer> stacks = stacksAt(tile.getWorldLocation());
                    for (TileItem item : items)
                    {
                        Integer tracked = stacks.get(item.getId());
                        stacks.put(item.getId(), (tracked == null ? 0 : tracked) + item.getQuantity());
                    }
                }
            }
        }
        valuableLootDirty = true;
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        groundItems.clear();
        valuableLoot = false;
        valuableLootDirty = true;
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (QolConfig.GROUP.equals(event.getGroup()) && event.getKey().startsWith("deprioLeave"))
        {
            reload();
            valuableLootDirty = true;
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        // Every load screen invalidates the scene the tracked tiles refer to.
        if (event.getGameState() == GameState.LOADING)
        {
            groundItems.clear();
            valuableLootDirty = true;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (pricesLoaded || groundItems.isEmpty())
        {
            return;
        }
        if (itemManager.getItemPrice(PRICE_PROBE_ITEM) > 0)
        {
            pricesLoaded = true;
            valuableLootDirty = true;
        }
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (!tracking())
        {
            return;
        }
        TileItem item = event.getItem();
        WorldPoint spot = locationOf(event.getTile());
        if (item == null || spot == null)
        {
            return;
        }
        Map<Integer, Integer> stacks = stacksAt(spot);
        Integer tracked = stacks.get(item.getId());
        stacks.put(item.getId(), (tracked == null ? 0 : tracked) + item.getQuantity());
        valuableLootDirty = true;
    }

    @Subscribe
    public void onItemDespawned(ItemDespawned event)
    {
        if (groundItems.isEmpty())
        {
            return;
        }
        TileItem item = event.getItem();
        WorldPoint spot = locationOf(event.getTile());
        if (item == null || spot == null)
        {
            return;
        }
        Map<Integer, Integer> stacks = groundItems.get(spot);
        if (stacks == null)
        {
            return;
        }
        // Non-stackable drops spawn one item each under the same id, so only
        // this item's quantity leaves the tracked total.
        Integer tracked = stacks.get(item.getId());
        int left = (tracked == null ? 0 : tracked) - item.getQuantity();
        if (left > 0)
        {
            stacks.put(item.getId(), left);
        }
        else
        {
            stacks.remove(item.getId());
        }
        if (stacks.isEmpty())
        {
            groundItems.remove(spot);
        }
        valuableLootDirty = true;
    }

    @Subscribe
    public void onItemQuantityChanged(ItemQuantityChanged event)
    {
        if (!tracking())
        {
            return;
        }
        TileItem item = event.getItem();
        WorldPoint spot = locationOf(event.getTile());
        if (item == null || spot == null)
        {
            return;
        }
        Map<Integer, Integer> stacks = stacksAt(spot);
        Integer tracked = stacks.get(item.getId());
        int total = (tracked == null ? 0 : tracked) + event.getNewQuantity() - event.getOldQuantity();
        if (total > 0)
        {
            stacks.put(item.getId(), total);
        }
        else
        {
            stacks.remove(item.getId());
            if (stacks.isEmpty())
            {
                groundItems.remove(spot);
            }
        }
        valuableLootDirty = true;
    }

    /**
     * Ground items only matter inside an instance. Tracked with the guard off
     * too, so turning it on mid-instance still sees the loot already down.
     */
    private boolean tracking()
    {
        return inInstance();
    }

    private Map<Integer, Integer> stacksAt(WorldPoint spot)
    {
        Map<Integer, Integer> stacks = groundItems.get(spot);
        if (stacks == null)
        {
            stacks = new HashMap<>();
            groundItems.put(spot, stacks);
        }
        return stacks;
    }

    // priority -1 so this lands after every other plugin's post-sort pass, and
    // is therefore the last word on click priority.
    @Subscribe(priority = -1)
    public void onPostMenuSort(PostMenuSort event)
    {
        if (!enabled || !inInstance() || !valuableLootAround())
        {
            return;
        }

        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        int first = -1;
        for (int i = 0; i < entries.length; i++)
        {
            if (isLeaveEntry(entries[i]))
            {
                first = i;
                break;
            }
        }

        if (first < 0)
        {
            lastSunk = 0;
            return;
        }

        boolean[] sunk = new boolean[entries.length];
        sunk[first] = true;
        int sunkCount = 1;
        for (int i = first + 1; i < entries.length; i++)
        {
            if (isLeaveEntry(entries[i]))
            {
                sunk[i] = true;
                sunkCount++;
            }
        }

        // Everything up to the anchor keeps its slot, the sunk entries take the
        // anchor's own slot, and the anchor and what follows shift up past them.
        int anchor = anchorIndex(entries, sunk);
        MenuEntry[] reordered = new MenuEntry[entries.length];
        int keptSlot = 0;
        int sunkSlot = anchor;
        for (int i = 0; i < entries.length; i++)
        {
            if (sunk[i])
            {
                reordered[sunkSlot++] = entries[i];
            }
            else
            {
                reordered[keptSlot < anchor ? keptSlot : keptSlot + sunkCount] = entries[i];
                keptSlot++;
            }
        }
        menu.setMenuEntries(reordered);
        if (sunkCount != lastSunk)
        {
            lastSunk = sunkCount;
            log.debug("[exit] sank {} leave entries below anchor index {}", sunkCount, anchor);
        }
    }

    private boolean isLeaveEntry(MenuEntry entry)
    {
        MenuAction type = entry.getType();
        if (OBJECT_OPTIONS.contains(type))
        {
            return matchesOption(objectOptions, entry.getOption());
        }
        if (WIDGET_OPTIONS.contains(type))
        {
            return matchesOption(itemOptions, entry.getOption());
        }
        return false;
    }

    /**
     * Option patterns are case-insensitive, so the raw option is matched
     * as-is on this per-frame path; tags are stripped only when present.
     */
    private static boolean matchesOption(List<Pattern> patterns, String option)
    {
        if (option == null)
        {
            return false;
        }
        String cleaned = (option.indexOf('<') >= 0 ? Text.removeTags(option) : option).trim();
        for (Pattern pattern : patterns)
        {
            if (pattern.matcher(cleaned).matches())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the deprioritized entries are re-inserted. Menu entries render
     * bottom-up, so inserting AT an anchor's index puts them below it: "Walk
     * here" when the menu has one, else "Use" (which the item options sit next
     * to), else index 0, the very bottom of the menu. The index counts only
     * the entries that are not sunk.
     */
    private int anchorIndex(MenuEntry[] entries, boolean[] sunk)
    {
        int use = -1;
        int kept = 0;
        for (int i = 0; i < entries.length; i++)
        {
            if (sunk[i])
            {
                continue;
            }
            MenuEntry entry = entries[i];
            if (entry.getType() == MenuAction.WALK)
            {
                return kept;
            }
            if (use < 0 && USE_OPTION.equalsIgnoreCase(Text.removeTags(entry.getOption())))
            {
                use = kept;
            }
            kept++;
        }
        return Math.max(use, 0);
    }

    private boolean inInstance()
    {
        WorldView worldView = client.getTopLevelWorldView();
        return worldView != null && worldView.isInstance();
    }

    private boolean valuableLootAround()
    {
        if (valuableLootDirty)
        {
            boolean was = valuableLoot;
            valuableLoot = scanValuableLoot();
            valuableLootDirty = false;
            if (was != valuableLoot)
            {
                log.debug("[exit] valuable loot on floor changed: {} (tiles={})", valuableLoot, groundItems.size());
            }
        }
        return valuableLoot;
    }

    private boolean scanValuableLoot()
    {
        int threshold = lootValue;
        for (Map<Integer, Integer> onTile : groundItems.values())
        {
            for (Map.Entry<Integer, Integer> stack : onTile.entrySet())
            {
                if (isValuable(stack.getKey(), stack.getValue(), threshold))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A cache miss on an unknown or delisted id can throw, which on this path
     * would take the menu build down with it, so a failed lookup only means
     * "this stack doesn't count" and the remaining stacks still get checked.
     */
    private boolean isValuable(int itemId, int quantity, int threshold)
    {
        try
        {
            long unitPrice = itemManager.getItemPrice(itemId);
            if (unitPrice * quantity >= threshold)
            {
                return true;
            }
            ItemComposition item = itemManager.getItemComposition(itemId);
            return item != null && matchesAny(whitelist, item.getName());
        }
        catch (RuntimeException ex)
        {
            return false;
        }
    }

    private WorldPoint locationOf(Tile tile)
    {
        return tile != null ? tile.getWorldLocation() : null;
    }

    private boolean matchesAny(List<Pattern> patterns, String value)
    {
        if (value == null)
        {
            return false;
        }
        String cleaned = Text.removeTags(value).trim().toLowerCase(Locale.ROOT);
        for (Pattern pattern : patterns)
        {
            if (pattern.matcher(cleaned).matches())
            {
                return true;
            }
        }
        return false;
    }

    private void reload()
    {
        enabled = config.deprioLeave();
        lootValue = config.deprioLeaveLootValue();
        whitelist = MenuText.globList(config.deprioLeaveWhitelist());
        objectOptions = MenuText.globList(config.deprioLeaveOptions(), Pattern.CASE_INSENSITIVE);
        itemOptions = MenuText.globList(config.deprioLeaveItemOptions(), Pattern.CASE_INSENSITIVE);
    }
}
