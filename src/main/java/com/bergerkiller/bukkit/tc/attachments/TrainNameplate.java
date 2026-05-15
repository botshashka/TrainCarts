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
import org.bukkit.entity.Player;
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
    /** Client-side render-distance multiplier; ~32 blocks at standard view distance. */
    private static final float VIEW_RANGE = 0.5f;

    private final MinecartGroup group;
    private final Matrix4x4 scratchTransform = new Matrix4x4();
    private final ArrayList<AttachmentViewer> filteredViewersBuffer = new ArrayList<>();
    private VirtualDisplayTextEntity entity;
    private double lastSyncX = Double.NaN, lastSyncY, lastSyncZ;
    private boolean transformDirty = true;

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
            if (entity != null) destroyAll();
            return;
        }

        String text = group.getProperties().getDisplayNameOrEmpty();
        if (text == null || text.isEmpty()) {
            if (entity != null) destroyAll();
            return;
        }

        MinecartMember<?> head = group.head();
        AttachmentControllerMember headAttachments = head.getAttachments();

        if (entity == null) {
            entity = new VirtualDisplayTextEntity(null);
            entity.getMetadata().set(
                    DisplayHandle.DATA_BILLBOARD_RENDER_CONSTRAINTS,
                    DisplayHandle.BILLBOARD_RENDER_CENTER);
            entity.getMetadata().set(DisplayHandle.DATA_VIEW_RANGE, VIEW_RANGE);
            entity.setStyleFlags(DisplayHandle.TextDisplayHandle.STYLE_FLAG_SHADOW);
            entity.setBackgroundColor(BACKGROUND_ARGB);
        }

        // Pure world-space translation: identity rotation keeps the billboard render constraint
        // working and avoids the head cart's rotation affecting computed display offset.
        // Translation lives at column 3 of the head transform; read directly to avoid allocating.
        Matrix4x4 headTransform = headAttachments.getLiveTransform();
        double tx = headTransform.m03;
        double ty = headTransform.m13 + Y_OFFSET;
        double tz = headTransform.m23;
        if (tx != lastSyncX || ty != lastSyncY || tz != lastSyncZ) {
            lastSyncX = tx;
            lastSyncY = ty;
            lastSyncZ = tz;
            transformDirty = true;
            scratchTransform.setIdentity();
            scratchTransform.translate(tx, ty, tz);
            entity.updatePosition(scratchTransform);
        }

        entity.setText(ChatText.fromMessage(text));

        // Passengers of this train don't need to see the label they're inside of.
        // Skip the filter pass when no cart has a player passenger (common case).
        Collection<AttachmentViewer> desiredViewers = headAttachments.getAttachmentViewers();
        if (groupHasPlayerPassengers()) {
            filteredViewersBuffer.clear();
            for (AttachmentViewer viewer : desiredViewers) {
                if (!isPassengerOfGroup(viewer.getPlayer())) {
                    filteredViewersBuffer.add(viewer);
                }
            }
            desiredViewers = filteredViewersBuffer;
        }
        reconcileViewers(desiredViewers);
    }

    private boolean groupHasPlayerPassengers() {
        for (MinecartMember<?> member : group) {
            if (member.getEntity() != null && member.getEntity().hasPlayerPassenger()) {
                return true;
            }
        }
        return false;
    }

    private boolean isPassengerOfGroup(Player player) {
        if (player == null) {
            return false;
        }
        for (MinecartMember<?> member : group) {
            if (member.getEntity() != null && member.getEntity().isPassenger(player)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pushes a position update to current viewers. Mirrors the cadence used by
     * the rest of the attachment system.
     *
     * @param absolute true for an absolute teleport, false for a relative move
     */
    public void syncPosition(boolean absolute) {
        if (entity == null || !entity.hasViewers()) {
            return;
        }
        // Always honor absolute resyncs; skip relative ones when the train hasn't moved
        // so stationary trains don't generate per-viewer metadata packet floods.
        if (!absolute && !transformDirty) {
            return;
        }
        entity.syncPosition(absolute);
        transformDirty = false;
    }

    /**
     * Destroys the nameplate for all viewers. Called when the train is removed
     * or unloaded.
     */
    public void destroyAll() {
        if (entity != null) {
            entity.destroyForAll();
            entity = null;
        }
        lastSyncX = Double.NaN;
        transformDirty = true;
    }

    private void reconcileViewers(Collection<AttachmentViewer> currentViewers) {
        // Fast path: viewer set unchanged. Avoids the O(V_current * V_entity) containment
        // work below in the typical case where viewer churn is rare.
        Collection<AttachmentViewer> entityViewers = entity.getViewers();
        if (entityViewers.size() == currentViewers.size()
                && entityViewers.containsAll(currentViewers)) {
            return;
        }

        // Spawn for newly-added viewers
        for (AttachmentViewer viewer : currentViewers) {
            if (!entity.isViewer(viewer)) {
                entity.spawn(viewer, new Vector());
            }
        }

        // Destroy for viewers that no longer see the head cart
        List<AttachmentViewer> toRemove = null;
        for (AttachmentViewer viewer : entityViewers) {
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
}
