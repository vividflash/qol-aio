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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;

// Blocks known spam game messages (e.g. "not enough inventory space", "spellbook reordering
// enabled/disabled") via the "chatFilterCheck" script callback, which fires right before a message
// is displayed; writing 0 to intStack[intStackSize - 3] vetoes it entirely.
//
// The callback covers every chat line, player chat included, so the message type on the stack is
// checked first and only the game's own message types are ever matched against the phrase list.
//
// Matches on configurable substrings (case-insensitive) rather than exact text.
@Slf4j
@Singleton
public class ChatSpamFilterFeature implements Feature
{
    // Message types the game itself produces. Player chat is not among them.
    private static final Set<ChatMessageType> GAME_MESSAGE_TYPES = EnumSet.of(
        ChatMessageType.GAMEMESSAGE,
        ChatMessageType.ENGINE,
        ChatMessageType.SPAM,
        ChatMessageType.ITEM_EXAMINE,
        ChatMessageType.NPC_EXAMINE,
        ChatMessageType.OBJECT_EXAMINE
    );

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private QolConfig config;

    private boolean enabled;
    // Lower-cased, trimmed, non-empty phrases; parsed on start and on change.
    private String[] phrases = new String[0];

    @Override
    public void startUp()
    {
        enabled = config.chatSpamFilterEnabled();
        phrases = parsePhrases(config.chatSpamFilterPhrases());
        if (log.isDebugEnabled())
        {
            log.debug("[config] enabled={} phrases={}", config.chatSpamFilterEnabled(), config.chatSpamFilterPhrases());
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
        if (!QolConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }
        if ("chatSpamFilterEnabled".equals(event.getKey()))
        {
            enabled = config.chatSpamFilterEnabled();
        }
        else if ("chatSpamFilterPhrases".equals(event.getKey()))
        {
            phrases = parsePhrases(config.chatSpamFilterPhrases());
        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event)
    {
        if (!enabled || phrases.length == 0)
        {
            return;
        }

        if (!"chatFilterCheck".equals(event.getEventName()))
        {
            return;
        }

        Object[] objectStack = client.getObjectStack();
        int objectStackSize = client.getObjectStackSize();
        if (objectStackSize <= 0 || !(objectStack[objectStackSize - 1] instanceof String))
        {
            return;
        }

        int[] intStack = client.getIntStack();
        int intStackSize = client.getIntStackSize();
        if (intStackSize < 3)
        {
            return;
        }

        if (!GAME_MESSAGE_TYPES.contains(ChatMessageType.of(intStack[intStackSize - 2])))
        {
            return;
        }

        String message = ((String) objectStack[objectStackSize - 1]).toLowerCase(Locale.ROOT);
        boolean blocked = false;
        for (String phrase : phrases)
        {
            if (message.contains(phrase))
            {
                blocked = true;
                break;
            }
        }

        if (blocked)
        {
            log.debug("[chatspam] blocked game message: {}", message);
            intStack[intStackSize - 3] = 0;
        }
    }

    private static String[] parsePhrases(String text)
    {
        List<String> out = new ArrayList<>();
        for (String phrase : text.split("[,\\r\\n]"))
        {
            String trimmed = phrase.trim();
            if (!trimmed.isEmpty())
            {
                out.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return out.toArray(new String[0]);
    }
}
