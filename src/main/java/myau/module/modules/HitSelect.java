package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final double COS_60_SQ = 0.25; // cos(60°)=0.5, squared=0.25
    private static final double CLOSE_RANGE_SQ = 6.25; // 2.5²
    private static final double MOVE_EPSILON_SQ = 1.0E-8;
    private static final int MAX_BLOCK_STREAK = 5;

    // sprint reset timing
    private static final int SPRINT_RESET_TICKS = 1;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"SECOND", "CRITICALS", "W_TAP"});

    private boolean sprintState;
    private boolean ksModified;
    private boolean ksWasEnabled;
    private double ksSavedSlowdown;
    private int blockStreak;
    private int ticksSinceBlock;
    private KeepSprint ksRef;

    // w-tap state
    private boolean waitingForSprint;
    private int sprintResetTick;

    // track last target for combo detection
    private int lastTargetId = -1;
    private int comboCount;

    public HitSelect() {
        super("HitSelect", false);
    }

    @Override
    public void onEnabled() {
        this.reset();
        Module m = Myau.moduleManager.modules.get(KeepSprint.class);
        this.ksRef = m instanceof KeepSprint ? (KeepSprint) m : null;
    }

    @Override
    public void onDisabled() {
        this.restoreKS();
        this.reset();
        this.ksRef = null;
    }

    private void reset() {
        this.sprintState = false;
        this.ksModified = false;
        this.ksWasEnabled = false;
        this.ksSavedSlowdown = 0.0;
        this.blockStreak = 0;
        this.ticksSinceBlock = 0;
        this.waitingForSprint = false;
        this.sprintResetTick = 0;
        this.lastTargetId = -1;
        this.comboCount = 0;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) return;

        if (event.getType() == EventType.PRE) {
            this.ticksSinceBlock++;

            // w-tap: if we stopped sprint to reset, re-sprint after delay
            if (this.waitingForSprint && this.ticksSinceBlock >= this.sprintResetTick) {
                this.waitingForSprint = false;
            }
            return;
        }

        // POST
        this.restoreKS();
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) return;

        Packet<?> packet = event.getPacket();

        if (packet instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction.Action action = ((C0BPacketEntityAction) packet).getAction();
            if (action == C0BPacketEntityAction.Action.START_SPRINTING) {
                this.sprintState = true;
            } else if (action == C0BPacketEntityAction.Action.STOP_SPRINTING) {
                this.sprintState = false;
            }
            return;
        }

        if (!(packet instanceof C02PacketUseEntity)) return;

        C02PacketUseEntity use = (C02PacketUseEntity) packet;
        if (use.getAction() != C02PacketUseEntity.Action.ATTACK) return;

        Entity raw = use.getEntityFromWorld(mc.theWorld);
        if (raw == null || !(raw instanceof EntityLivingBase) || raw instanceof EntityLargeFireball) return;

        EntityLivingBase target = (EntityLivingBase) raw;
        EntityPlayer player = mc.thePlayer;
        if (player == null) return;

        // track combo on same target
        int targetId = target.getEntityId();
        if (targetId != this.lastTargetId) {
            this.lastTargetId = targetId;
            this.comboCount = 0;
            this.blockStreak = 0;
        }

        // safety: never block more than MAX_BLOCK_STREAK in a row
        if (this.blockStreak >= MAX_BLOCK_STREAK) {
            this.blockStreak = 0;
            this.comboCount++;
            return;
        }

        boolean allow;
        switch (this.mode.getValue()) {
            case 0:
                allow = this.checkSecond(player, target);
                break;
            case 1:
                allow = this.checkCritical(player, target);
                break;
            case 2:
                allow = this.checkWTap(player, target);
                break;
            default:
                return;
        }

        if (allow) {
            this.blockStreak = 0;
            this.comboCount++;
            this.ticksSinceBlock = 0;
        } else {
            event.setCancelled(true);
            this.blockStreak++;
            this.ticksSinceBlock = 0;
        }
    }

    // ===================== MODE LOGIC =====================

    private boolean checkSecond(EntityPlayer player, EntityLivingBase target) {
        // target in hurt animation -> stack hits for combo
        if (target.hurtTime > 0) return true;

        // we're being hit -> trade immediately
        if (player.hurtTime > 0) return true;

        // close range -> no time to wait
        double dx = player.posX - target.posX;
        double dz = player.posZ - target.posZ;
        double distSq = dx * dx + dz * dz;
        if (distSq < CLOSE_RANGE_SQ) return true;

        // already in a combo -> keep swinging
        if (this.comboCount >= 2) return true;

        // check if both approaching each other
        if (!movingTowards(player, target) || !movingTowards(target, player)) return true;

        // calculate relative closing speed to decide if waiting is worth it
        double closingSpeed = getClosingSpeed(player, target);
        if (closingSpeed <= 0.0) return true; // not closing in

        // estimate ticks until in range (distance / closing speed)
        double dist = Math.sqrt(distSq);
        double ticksToContact = dist / closingSpeed;

        // if contact is imminent (< 3 ticks), just swing
        if (ticksToContact < 3.0) return true;

        this.applyKS();
        return false;
    }

    private boolean checkCritical(EntityPlayer player, EntityLivingBase target) {
        // on ground -> normal hit
        if (player.onGround) return true;

        // being hurt -> trade
        if (player.hurtTime > 0) return true;

        // actually falling -> crit window
        if (player.motionY < -0.08 && player.fallDistance > 0.0f) return true;

        // near peak of jump (motionY very small) and about to fall -> almost there, allow
        if (player.motionY < 0.0 && player.motionY > -0.08 && player.fallDistance > 0.0f) {
            // close enough to crit threshold, allow if target is in range
            double dx = player.posX - target.posX;
            double dz = player.posZ - target.posZ;
            if (dx * dx + dz * dz < 9.0) return true; // 3.0²
        }

        // in water/lava/on ladder -> can't crit anyway, just hit
        if (player.isInWater() || player.isInLava() || player.isOnLadder()) return true;

        this.applyKS();
        return false;
    }

    private boolean checkWTap(EntityPlayer player, EntityLivingBase target) {
        // collided -> sprint is unreliable
        if (player.isCollidedHorizontally) return true;

        // not holding W -> can't sprint
        if (!mc.gameSettings.keyBindForward.isKeyDown()) return true;

        // already sprinting -> this hit will have sprint KB
        if (this.sprintState) return true;

        // target already in combo (hurt) -> less important to w-tap
        if (target.hurtTime > 0 && this.comboCount >= 1) return true;

        // if we recently blocked, and sprint hasn't reset yet, keep blocking
        // but don't block forever (handled by MAX_BLOCK_STREAK)

        this.applyKS();
        return false;
    }

    // ===================== MATH =====================

    /**
     * Checks if source is moving towards target within 60° cone.
     * Zero sqrt calls — uses squared dot product comparison.
     */
    private static boolean movingTowards(EntityLivingBase source, EntityLivingBase target) {
        double mx = source.posX - source.lastTickPosX;
        double mz = source.posZ - source.lastTickPosZ;
        double moveSq = mx * mx + mz * mz;
        if (moveSq < MOVE_EPSILON_SQ) return false;

        double tx = target.posX - source.posX;
        double tz = target.posZ - source.posZ;
        double targSq = tx * tx + tz * tz;
        if (targSq < MOVE_EPSILON_SQ) return false;

        double dot = mx * tx + mz * tz;
        if (dot <= 0.0) return false;

        // dot² >= cos²(60) * |move|² * |targ|²
        return dot * dot >= COS_60_SQ * moveSq * targSq;
    }

    /**
     * Returns how fast two entities are closing in on each other (blocks/tick).
     * Positive = getting closer. No sqrt on movement vectors.
     */
    private static double getClosingSpeed(EntityLivingBase a, EntityLivingBase b) {
        // direction from a to b (not normalized, but we only care about projection)
        double dx = b.posX - a.posX;
        double dz = b.posZ - a.posZ;
        double distSq = dx * dx + dz * dz;
        if (distSq < MOVE_EPSILON_SQ) return 0.0;

        double invDist = 1.0 / Math.sqrt(distSq); // one sqrt for the whole method
        double nx = dx * invDist;
        double nz = dz * invDist;

        // a's velocity projected onto direction towards b
        double aVelX = a.posX - a.lastTickPosX;
        double aVelZ = a.posZ - a.lastTickPosZ;
        double aProj = aVelX * nx + aVelZ * nz;

        // b's velocity projected onto direction towards a (negative direction)
        double bVelX = b.posX - b.lastTickPosX;
        double bVelZ = b.posZ - b.lastTickPosZ;
        double bProj = bVelX * nx + bVelZ * nz;

        // closing speed = a moving towards b + b moving towards a
        return aProj - bProj;
    }

    // ===================== KEEPSPRINT =====================

    private void applyKS() {
        if (this.ksModified || this.ksRef == null) return;

        this.ksSavedSlowdown = this.ksRef.slowdown.getValue().doubleValue();
        this.ksWasEnabled = this.ksRef.isEnabled();

        if (!this.ksWasEnabled) this.ksRef.toggle();
        this.ksRef.slowdown.setValue(0);
        this.ksModified = true;
    }

    private void restoreKS() {
        if (!this.ksModified || this.ksRef == null) return;

        this.ksRef.slowdown.setValue((int) this.ksSavedSlowdown);
        if (!this.ksWasEnabled && this.ksRef.isEnabled()) this.ksRef.toggle();

        this.ksModified = false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
