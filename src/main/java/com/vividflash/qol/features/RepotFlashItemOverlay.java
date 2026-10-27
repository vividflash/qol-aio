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
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

// Pulses the inventory slots of potions the repot flash wants re-drunk, alternating between the two
// configured colors. Registered by RepotFlashFeature. Nothing shows when no matching potion is
// carried: an absent item has no inventory slot to mark.
@Singleton
public class RepotFlashItemOverlay extends WidgetItemOverlay
{
    private final RepotFlashFeature feature;

    // Alternation half for the frame being drawn, read once in render.
    private boolean firstColor;

    @Inject
    RepotFlashItemOverlay(RepotFlashFeature feature)
    {
        this.feature = feature;
        showOnInventory();
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!feature.anyInventoryTarget()
            || feature.flashStyle() == RepotFlashStyle.FULL_INVENTORY)
        {
            return null;
        }
        firstColor = feature.flashFirstColor();
        return super.render(graphics);
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
    {
        if (!feature.shouldFlashItem(itemId))
        {
            return;
        }

        Rectangle bounds = widgetItem.getCanvasBounds();
        if (bounds == null)
        {
            return;
        }
        graphics.setColor(feature.flashColor(firstColor));
        graphics.fill(bounds);
        graphics.setColor(feature.flashBorderColor(firstColor));
        graphics.draw(bounds);
    }
}
