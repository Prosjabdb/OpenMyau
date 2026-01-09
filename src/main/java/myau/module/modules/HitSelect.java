package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.Vec3;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Mode property with three options: SECOND, CRITICALS, W_TAP
    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"SECOND", "CRITICALS", "W_TAP"});
    
    // Configurable properties for SECOND mode (using FloatProperty as DoubleProperty doesn't exist)
    public final FloatProperty secondMaxAngle = new FloatProperty("Second Max Angle", 60.0F, 0.0F, 180.0F);
    public final FloatProperty secondMinDist = new FloatProperty("Second Min Distance", 2.5F, 0.0F, 10.0F);
    
    // Configurable property for CRITICALS mode (fall distance threshold)
    public final FloatProperty critFallThreshold = new FloatProperty("Crit Fall Threshold", 0.0F, 0.0F, 5.0F);
    
    private boolean sprintState = false;
    private boolean set = false;
    private double savedSlowdown = 0.0;
    
    private int blockedHits = 0;
    private int allowedHits = 0;

    public HitSelect() {
        super("HitSelect", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        
        if (event.getType() == EventType.POST) {
            this.resetMotion();
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }

        if (event.getPacket() instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction packet = (C0BPacketEntityAction) event.getPacket();
            switch (packet.getAction()) {
                case START_SPRINTING:
                    this.sprintState = true;
                    break;
                case STOP_SPRINTING:
                    this.sprintState = false;
                    break;
            }
            return;
        }

        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
            
            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) {
                return;
            }

            Entity target = use.getEntityFromWorld(mc.theWorld);
            if (target == null || target instanceof EntityLargeFireball) {
                return;
            }

            if (!(target instanceof EntityLivingBase)) {
                return;
            }

            EntityLivingBase living = (EntityLivingBase) target;
            boolean allow = true;

            switch (this.mode.getValue()) {
                case 0: // SECOND
                    allow = this.prioritizeSecondHit(mc.thePlayer, living);
                    break;
                case 1: // CRITICALS
                    allow = this.prioritizeCriticalHits(mc.thePlayer);
                    break;
                case 2: // W_TAP
                    allow = this.prioritizeWTapHits(mc.thePlayer, this.sprintState);
                    break;
            }

            if (!allow) {
                event.setCancelled(true);
                this.blockedHits++;
            } else {
                this.allowedHits++;
            }
        }
    }

    /**
     * SECOND mode: Prioritizes second hits by blocking initial hits under certain conditions.
     * Allows if target is hurt, player is recovering, too close, or not moving towards each other.
     */
    private boolean prioritizeSecondHit(EntityLivingBase player, EntityLivingBase target) {
        // If target is already hurt (second hit opportunity), allow
        if (target.hurtTime != 0) {
            return true;
        }

        // If player hasn't recovered from hurt time (allow during recovery), allow
        if (player.hurtTime > 0 && player.hurtTime <= player.maxHurtTime - 1) {
            return true;
        }

        // If too close (configurable distance), allow
        double dist = player.getDistanceToEntity(target);
        if (dist < this.secondMinDist.getValue()) {
            return true;
        }

        // If not moving towards each other (within configurable angle), allow
        if (!this.isMovingTowards(target, player, this.secondMaxAngle.getValue())) {
            return true;
        }

        if (!this.isMovingTowards(player, target, this.secondMaxAngle.getValue())) {
            return true;
        }

        // Block the hit and fix motion to prevent sprint reset
        this.fixMotion();
        return false;
    }

    /**
     * CRITICALS mode: Prioritizes critical hits by blocking non-critical attacks.
     * Allows if on ground, hurt, or falling (above threshold).
     */
    private boolean prioritizeCriticalHits(EntityLivingBase player) {
        // If on ground, allow (ground crits)
        if (player.onGround) {
            return true;
        }

        // If hurt, allow (during hurt animation)
        if (player.hurtTime != 0) {
            return true;
        }

        // If falling above threshold, allow (air crits)
        if (player.fallDistance > this.critFallThreshold.getValue()) {
            return true;
        }

        // Block the hit and fix motion
        this.fixMotion();
        return false;
    }

    /**
     * W_TAP mode: Prioritizes W-Tap hits by blocking when sprinting forward without tapping.
     * Allows if against wall, not moving forward, or already sprinting.
     */
    private boolean prioritizeWTapHits(EntityLivingBase player, boolean sprinting) {
        // If against wall, allow
        if (player.isCollidedHorizontally) {
            return true;
        }

        // If not moving forward, allow
        if (!mc.gameSettings.keyBindForward.isKeyDown()) {
            return true;
        }

        // If already sprinting, allow
        if (sprinting) {
            return true;
        }

        // Block the hit and fix motion
        this.fixMotion();
        return false;
    }

    /**
     * Fixes motion by enabling KeepSprint and setting slowdown to 0 to prevent sprint reset on hit.
     */
    private void fixMotion() {
        if (this.set) {
            return;
        }

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }

        try {
            // Save the current slowdown value
            this.savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
            
            // Enable KeepSprint and set slowdown to 0
            if (!keepSprint.isEnabled()) {
                keepSprint.toggle();
            }
            keepSprint.slowdown.setValue(0.0F);  // Assuming slowdown is FloatProperty
            
            this.set = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Resets motion by restoring KeepSprint settings.
     */
    private void resetMotion() {
        if (!this.set) {
            return;
        }

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }

        try {
            // Restore the original slowdown value
            keepSprint.slowdown.setValue((float) this.savedSlowdown);
            
            // Disable KeepSprint if we enabled it
            if (keepSprint.isEnabled()) {
                keepSprint.toggle();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.set = false;
        this.savedSlowdown = 0.0;
    }

    /**
     * Checks if the source entity is moving towards the target within a given angle.
     */
    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngle) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();

        // Calculate movement vector
        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);

        // If not moving, return false
        if (movementLength == 0.0) {
            return false;
        }

        // Normalize movement vector
        mx /= movementLength;
        mz /= movementLength;

        // Calculate vector to target
        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);

        // If target is at same position, return false
        if (targetLength == 0.0) {
            return false;
        }

        // Normalize target vector
        tx /= targetLength;
        tz /= targetLength;

        // Calculate dot product (cosine of angle between vectors)
        double dotProduct = mx * tx + mz * tz;

        // Check if angle is within threshold
        return dotProduct >= Math.cos(Math.toRadians(maxAngle));
    }

    @Override
    public void onDisabled() {
        this.resetMotion();
        this.sprintState = false;
        this.set = false;
        this.savedSlowdown = 0.0;
        this.blockedHits = 0;
        this.allowedHits = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString(), "B:" + this.blockedHits, "A:" + this.allowedHits};
    }
}
