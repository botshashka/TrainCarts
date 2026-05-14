package com.bergerkiller.bukkit.tc.attachments;

import com.bergerkiller.bukkit.common.internal.CommonCapabilities;
import com.bergerkiller.bukkit.common.math.Matrix4x4;
import com.bergerkiller.bukkit.common.wrappers.ChatText;
import com.bergerkiller.bukkit.tc.attachments.api.AttachmentViewer;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.components.AttachmentControllerMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.generated.net.minecraft.world.entity.DisplayHandle;
import org.bukkit.Color;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A floating text label above the lead cart of a train showing the train's
 * configured display name. Lets players visually distinguish trains belonging
 * to different lines when the carts themselves look identical.
 *
 * Sourced from {@link TrainProperties#getDisplayNameOrEmpty()}: an empty
 * display name hides the label entirely, so the feature is opt-in per train
 * via {@code /train displayname <name>}. Minecraft chat color codes embedded
 * in the name (e.g. {@code &cRed Line}) are honored.
 *
 * Requires a server with MC 1.19.4+ TextDisplay support. On older servers,
 * {@link #isSupported()} is false and instances are never constructed.
 */
public class TrainNameplate {
    /** Vertical offset above the head cart's wheel-center position. */
    private static final double Y_OFFSET = 1.5;
    /** Slightly translucent dark background so the text reads against any sky / terrain. */
    private static final int BACKGROUND_ARGB = Color.fromARGB(160, 0, 0, 0).asARGB();

    private final MinecartGroup group;
    private VirtualDisplayTextEntity entity;
    private String lastText = "";

    public TrainNameplate(MinecartGroup group) {
        this.group = group;
    }

    public static boolean isSupported() {
        return CommonCapabilities.HAS_DISPLAY_ENTITY
                && VirtualDisplayEntity.TEXT_DISPLAY_ENTITY_TYPE != null;
    }

    /**
     * Per-tick update: reconciles text and viewer set against the current head cart.
     * Cheap when nothing changed.
     */
    public void tick() {
        if (group.isEmpty() || group.isUnloaded()) {
            destroyAll();
            return;
        }

        String text = group.getProperties().getDisplayNameOrEmpty();
        if (text == null || text.isEmpty()) {
            destroyAll();
            return;
        }

        MinecartMember<?> head = group.head();
        AttachmentControllerMember headAttachments = head.getAttachments();

        if (entity == null) {
            entity = new VirtualDisplayTextEntity(null);
            entity.getMetadata().set(
                    DisplayHandle.DATA_BILLBOARD_RENDER_CONSTRAINTS,
                    DisplayHandle.BILLBOARD_RENDER_CENTER);
            entity.setStyleFlags(DisplayHandle.TextDisplayHandle.STYLE_FLAG_SHADOW);
            entity.setBackgroundColor(BACKGROUND_ARGB);
        }

        entity.updatePosition(buildTransform(headAttachments.getLiveTransform()));

        if (!text.equals(lastText)) {
            lastText = text;
            entity.setText(ChatText.fromMessage(text));
        }

        reconcileViewers(headAttachments.getAttachmentViewers());
    }

    /**
     * Pushes a position update to current viewers. Mirrors the cadence used by
     * the rest of the attachment system.
     *
     * @param absolute true for an absolute teleport, false for a relative move
     */
    public void syncPosition(boolean absolute) {
        if (entity != null && entity.hasViewers()) {
            entity.syncPosition(absolute);
        }
    }

    /**
     * Destroys the nameplate for all viewers. Called when the train is removed
     * or unloaded.
     */
    public void destroyAll() {
        if (entity != null) {
            entity.destroyForAll();
        }
    }

    private void reconcileViewers(Collection<AttachmentViewer> currentViewers) {
        // Spawn for newly-added viewers
        for (AttachmentViewer viewer : currentViewers) {
            if (!entity.isViewer(viewer)) {
                entity.spawn(viewer, new Vector());
            }
        }

        // Destroy for viewers that no longer see the head cart
        List<AttachmentViewer> toRemove = null;
        for (AttachmentViewer viewer : entity.getViewers()) {
            if (!currentViewers.contains(viewer)) {
                if (toRemove == null) {
                    toRemove = new ArrayList<>();
                }
                toRemove.add(viewer);
            }
        }
        if (toRemove != null) {
            for (AttachmentViewer viewer : toRemove) {
                entity.destroy(viewer);
            }
        }
    }

    private static Matrix4x4 buildTransform(Matrix4x4 headTransform) {
        Vector pos = headTransform.toVector();
        pos.add(new Vector(0.0, Y_OFFSET, 0.0));
        Matrix4x4 m = new Matrix4x4();
        m.translate(pos);
        return m;
    }
}
