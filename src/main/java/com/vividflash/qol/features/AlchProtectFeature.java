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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.ScriptID;
import net.runelite.api.StructComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Alchemy protection: hides items from being alched or explorer's-ring'd when
 * they are untradeable, in the collection log, or on a protection list. Aimed
 * at stopping an accidental alch of an important untradeable.
 *
 * Mechanism: while an alch spell is selected, or the Explorer's ring alchemy
 * interface is open, hide the protected inventory item widgets so they can't be
 * clicked. Restore them when alching ends.
 */
@Slf4j
@Singleton
public class AlchProtectFeature implements Feature
{
    private enum AlchMode
    {
        NONE, SPELL, RING
    }

    // Collection log cache layout. There are five tabs; each tab struct exposes an
    // enum of page structs (param 683), and each page struct exposes an enum of
    // that page's item ids (param 690).
    private static final int[] COLLECTION_LOG_TAB_STRUCT_IDS = {471, 472, 473, 474, 475};
    private static final int TAB_PAGES_ENUM_PARAM_ID = 683;
    private static final int PAGE_ITEMS_ENUM_PARAM_ID = 690;

    // Spell names as they appear in the menu target.
    private static final String LOW_ALCHEMY_SPELL = "Low Level Alchemy";
    private static final String HIGH_ALCHEMY_SPELL = "High Level Alchemy";

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

    private AlchMode mode = AlchMode.NONE;
    private boolean wasDragging = false;
    private final Set<Widget> hiddenItems = new HashSet<>();
    private boolean hidePending;

    // Config cached on start and on change; read on every inventory redraw.
    private boolean enabled;
    private boolean protectUntradeables;
    private boolean protectCollectionLog;
    private Set<String> protectList = new HashSet<>();

    // Populated once from the cache on the client thread; unnoted item ids only.
    private Set<Integer> collectionLogItemIds = null;

    @Override
    public void startUp()
    {
        enabled = config.alchProtect();
        protectUntradeables = config.alchProtectUntradeables();
        protectCollectionLog = config.alchProtectCollectionLog();
        protectList = parseProtectList(config.alchProtectList());
        if (log.isDebugEnabled())
        {
            log.debug("[config] enabled={} untradeables={} collectionLog={} list={}",
                enabled, protectUntradeables, protectCollectionLog, config.alchProtectList());
        }
        eventBus.register(this);
        // If the plugin starts while already logged in, the cache is available now.
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            clientThread.invoke(this::buildCollectionLogSet);
        }
    }

    @Override
    public void shutDown()
    {
        showHidden();
        eventBus.unregister(this);
        mode = AlchMode.NONE;
        hidePending = false;
    }

    private boolean isAlching()
    {
        return mode != AlchMode.NONE;
    }

    /**
     * Hidden item widgets stay hidden until something un-hides them, so
     * switching the feature off has to release them right away rather than
     * leave the player with slots they cannot click.
     */
    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!QolConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }
        String key = event.getKey();
        if ("alchProtectUntradeables".equals(key))
        {
            protectUntradeables = config.alchProtectUntradeables();
            return;
        }
        if ("alchProtectCollectionLog".equals(key))
        {
            protectCollectionLog = config.alchProtectCollectionLog();
            return;
        }
        if ("alchProtectList".equals(key))
        {
            protectList = parseProtectList(config.alchProtectList());
            return;
        }
        if (!"alchProtect".equals(key))
        {
            return;
        }
        enabled = config.alchProtect();
        if (!enabled)
        {
            log.debug("[alch] feature switched off, releasing {} hidden slots", hiddenItems.size());
            mode = AlchMode.NONE;
            showHidden();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        // Build the collection-log id set once, the first time the cache is ready.
        if (event.getGameState() == GameState.LOGGED_IN && collectionLogItemIds == null)
        {
            buildCollectionLogSet();
        }
        // A spell selection does not survive logout or a hop.
        else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
        {
            mode = AlchMode.NONE;
            hidePending = false;
            showHidden();
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!enabled)
        {
            if (isAlching())
            {
                mode = AlchMode.NONE;
                showHidden();
            }
            return;
        }

        String option = Text.removeTags(event.getMenuOption());
        String targetText = Text.removeTags(event.getMenuTarget());

        if (option.contains("Cast") && !targetText.contains("->")
            && (targetText.contains(LOW_ALCHEMY_SPELL) || targetText.contains(HIGH_ALCHEMY_SPELL)))
        {
            if (mode != AlchMode.SPELL)
            {
                log.debug("[alch] alch spell selected, protecting items: target={}", targetText);
            }
            mode = AlchMode.SPELL;
        }
        else if (mode == AlchMode.SPELL)
        {
            // Any other click cancels a pending spell-alch selection.
            log.debug("[alch] spell selection cancelled by click: option={} target={}", option, targetText);
            mode = AlchMode.NONE;
            showHidden();
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (event.getScriptId() != ScriptID.INVENTORY_DRAWITEM)
        {
            return;
        }
        // The redraw is also the restore path, so it stays ungated: with the
        // feature switched off mid-alch there would otherwise be nothing left
        // to un-hide the slots.
        if (enabled && isAlching())
        {
            hideItems();
        }
        else
        {
            showHidden();
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() == InterfaceID.LUMBRIDGE_ALCHEMY && enabled)
        {
            log.debug("[alch] explorer's ring alchemy interface opened, protecting items");
            mode = AlchMode.RING;
            hideItems();
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        if (event.getGroupId() == InterfaceID.LUMBRIDGE_ALCHEMY)
        {
            mode = AlchMode.NONE;
            showHidden();
        }
    }

    @Subscribe
    public void onVarClientIntChanged(VarClientIntChanged event)
    {
        if (mode != AlchMode.RING)
        {
            return;
        }
        if (!enabled)
        {
            showHidden();
            return;
        }
        if (event.getIndex() == VarClientID.TOPLEVEL_PANEL)
        {
            int tab = client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL);
            if (tab == 3) // Inventory tab
            {
                showHidden();
            }
            else if (tab == 6) // Magic spellbook (where explorer-ring alch happens)
            {
                hideItems();
            }
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (mode == AlchMode.RING && event.getContainerId() == InventoryID.INV && enabled)
        {
            hideItems();
        }
    }

    @Subscribe
    public void onPostClientTick(PostClientTick event)
    {
        if (!wasDragging && !isAlching())
        {
            return;
        }

        // After a drag ends, re-evaluate (items may have moved slots).
        boolean dragging = client.getDraggedWidget() != null;
        if (!dragging && wasDragging)
        {
            wasDragging = false;
            if (isAlching())
            {
                showHidden();
                hideItems();
            }
        }
        else if (dragging && isAlching())
        {
            wasDragging = true;
        }
    }

    private void hideItems()
    {
        // The inventory redraw fires once per slot, so one queued pass covers
        // the whole burst.
        if (hidePending)
        {
            return;
        }
        hidePending = true;
        clientThread.invokeAtTickEnd(this::hideItemsTask);
    }

    private void hideItemsTask()
    {
        hidePending = false;
        Widget container = mode == AlchMode.RING
            ? client.getWidget(InterfaceID.LumbridgeAlchemy.ITEMS)
            : client.getWidget(InterfaceID.Inventory.ITEMS);
        if (container == null)
        {
            return;
        }
        Widget[] items = container.getChildren();
        if (items == null)
        {
            return;
        }

        int newlyHidden = 0;
        for (Widget item : items)
        {
            if (item.isHidden() || item.getItemId() <= 0)
            {
                continue;
            }
            if (shouldProtect(item.getItemId()))
            {
                item.setHidden(true);
                hiddenItems.add(item);
                newlyHidden++;
            }
        }
        if (newlyHidden > 0)
        {
            log.debug("[alch] hid {} protected items, mode={}", newlyHidden, mode);
        }
    }

    private boolean shouldProtect(int itemId)
    {
        ItemComposition item = itemManager.getItemComposition(itemId);
        if (!protectList.isEmpty() && protectList.contains(Text.removeTags(item.getName()).toLowerCase(Locale.ROOT)))
        {
            return true;
        }

        // getLinkedNoteId() points both ways: on a noted item it gives the
        // unnoted id, on an unnoted item the noted one. Only the first
        // direction is a canonicalisation, so it is taken only when getNote()
        // says this really is a note. ItemManager.canonicalize does the same.
        int unnoted = itemManager.canonicalize(itemId);

        if (protectCollectionLog && collectionLogItemIds != null
            && collectionLogItemIds.contains(unnoted))
        {
            // Collection log entries are stored as unnoted ids.
            return true;
        }

        if (protectUntradeables)
        {
            // Noted items report as untradeable, so read tradeability off the
            // unnoted form.
            ItemComposition unnotedItem = unnoted == itemId ? item : itemManager.getItemComposition(unnoted);
            return !unnotedItem.isTradeable();
        }
        return false;
    }

    /**
     * Walks the collection log cache structures once and caches every item id.
     * Must run on the client thread (cache reads). Roughly a few hundred struct
     * reads plus a few hundred enum reads on login, then never again.
     */
    private void buildCollectionLogSet()
    {
        if (collectionLogItemIds != null)
        {
            return;
        }
        Set<Integer> ids = new HashSet<>();
        try
        {
            for (int tabStructId : COLLECTION_LOG_TAB_STRUCT_IDS)
            {
                StructComposition tabStruct = client.getStructComposition(tabStructId);
                if (tabStruct == null)
                {
                    continue;
                }
                EnumComposition pagesEnum = client.getEnum(tabStruct.getIntValue(TAB_PAGES_ENUM_PARAM_ID));
                if (pagesEnum == null)
                {
                    continue;
                }
                for (int pageStructId : pagesEnum.getIntVals())
                {
                    StructComposition pageStruct = client.getStructComposition(pageStructId);
                    if (pageStruct == null)
                    {
                        continue;
                    }
                    EnumComposition itemsEnum = client.getEnum(pageStruct.getIntValue(PAGE_ITEMS_ENUM_PARAM_ID));
                    if (itemsEnum == null)
                    {
                        continue;
                    }
                    for (int id : itemsEnum.getIntVals())
                    {
                        ids.add(id);
                    }
                }
            }
        }
        catch (RuntimeException ex)
        {
            // Cache not ready or layout changed: leave collection-log protection
            // inactive rather than crashing the feature. Retried on next login.
            log.debug("[alch] collection log cache walk failed", ex);
            return;
        }
        if (ids.isEmpty())
        {
            // An empty walk means the struct/param layout did not resolve.
            // Leave the cache unset so the next login tries again.
            return;
        }
        collectionLogItemIds = ids;
        log.debug("[alch] collection log cache built: {} item ids", ids.size());
    }

    private void showHidden()
    {
        if (hiddenItems.isEmpty())
        {
            return;
        }
        clientThread.invokeAtTickEnd(() ->
        {
            for (Widget item : hiddenItems)
            {
                item.setHidden(false);
            }
            hiddenItems.clear();
        });
    }

    private static Set<String> parseProtectList(String text)
    {
        Set<String> out = new HashSet<>();
        for (String entry : text.split(","))
        {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty())
            {
                out.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
