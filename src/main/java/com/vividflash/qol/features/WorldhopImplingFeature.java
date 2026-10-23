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
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

/**
 * Blocks the world-switcher (via its menu click) while a configured impling type
 * is in the loaded scene, so you don't hop away and lose a catch. Impling
 * presence is detected by NPC name against the configured list.
 */
@Slf4j
@Singleton
public class WorldhopImplingFeature implements Feature
{
    private List<Pattern> implingPatterns = List.of();

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
            log.debug("[config] enabled={} types={} patterns={} showBlockMessages={}",
                config.worldhopImplingGuard(), config.worldhopImplingTypes(), implingPatterns.size(),
                config.showBlockMessages());
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
        if (QolConfig.GROUP.equals(event.getGroup()) && "worldhopImplingTypes".equals(event.getKey()))
        {
            reload();
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        // The widget-group test comes before the setting, so ordinary clicks
        // return without a config read.
        if (implingPatterns.isEmpty() || !isWorldHopOption(event) || !config.worldhopImplingGuard())
        {
            return;
        }

        NPC impling = findGuardedImpling();
        if (impling != null)
        {
            event.consume();
            log.debug("[impling] world-hop blocked: impling={} option={}", impling.getName(), event.getMenuOption());
            if (config.showBlockMessages())
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "[Safety] " + impling.getName() + " nearby, world-hop blocked. Catch it first.", null);
            }
        }
    }

    private boolean isWorldHopOption(MenuOptionClicked event)
    {
        int group = WidgetUtil.componentToInterface(event.getParam1());
        if (group != InterfaceID.WORLDSWITCHER && group != InterfaceID.LOGOUT)
        {
            return false;
        }

        String option = Text.removeTags(event.getMenuOption()).toLowerCase(Locale.ROOT);
        // World-switcher list entries use "Switch"; the logout panel uses similar wording.
        return option.equals("switch") || option.startsWith("switch to") || option.contains("hop-to")
            || option.contains("world-hop");
    }

    private NPC findGuardedImpling()
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return null;
        }

        for (NPC npc : worldView.npcs())
        {
            String name = npc.getName();
            if (name == null)
            {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            for (Pattern p : implingPatterns)
            {
                if (p.matcher(lower).matches())
                {
                    return npc;
                }
            }
        }
        return null;
    }

    private void reload()
    {
        implingPatterns = MenuText.globList(config.worldhopImplingTypes());
    }
}
