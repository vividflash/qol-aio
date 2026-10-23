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

import com.vividflash.qol.RepotFlashStyle;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Pulses the whole inventory area between the two configured colors while the
 * repot flash is active. The louder variant of {@link RepotFlashItemOverlay}.
 * Registered by {@link RepotFlashFeature}. Only drawn while a
 * matching potion is actually carried (nothing to click otherwise).
 */
@Singleton
public class RepotFlashInventoryOverlay extends Overlay
{
    private final Client client;
    private final RepotFlashFeature feature;

    @Inject
    RepotFlashInventoryOverlay(Client client, RepotFlashFeature feature)
    {
        this.client = client;
        this.feature = feature;
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPosition(OverlayPosition.DYNAMIC);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!feature.anyInventoryTarget()
            || feature.flashStyle() == RepotFlashStyle.POTION_ITEMS)
        {
            return null;
        }

        Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
        if (inventory == null || inventory.isHidden())
        {
            return null;
        }

        Rectangle bounds = inventory.getBounds();
        if (bounds == null || bounds.isEmpty())
        {
            return null;
        }
        boolean first = feature.flashFirstColor();
        graphics.setColor(feature.flashColor(first));
        graphics.fill(bounds);
        graphics.setColor(feature.flashBorderColor(first));
        graphics.draw(bounds);
        return null;
    }
}
