package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.TickEvent;
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
    
    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"SECOND", "CRITICALS", "W_TAP"});
    public final FloatProperty secondMaxAngle = new FloatProperty("Second Max Angle", 60.0F, 0.0F, 180.0F);
    public final FloatProperty secondMinDist = new FloatProperty("Second Min Distance", 2.5F, 0.0F, 10.0F);
    public final FloatProperty critFallThreshold = new FloatProperty("Crit Fall Threshold", 0.0F, 0.0F, 5.0F);
    public final FloatProperty randomization = new FloatProperty("Randomization", 0.1F, 0.0F, 1.0F);
    
    private boolean sprintState = false;
    private boolean set = false;
    private double savedSlowdown = 0.0;
    
    private int blockedHits = 0;
    private int allowedHits = 0;

    public HitSelect() {
        super("HitSelect", false);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        this.resetMotion();
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

    private boolean prioritizeSecondHit(EntityLivingBase player, EntityLivingBase target) {
        if (target.hurtTime != 0) {
            return true;
        }
        if (player.hurtTime > 0 && player.hurtTime <= player.maxHurtTime - 1) {
            return true;
        }
        double dist = player.getDistanceToEntity(target);
        if (dist < this.secondMinDist.getValue()) {
            return true;
        }
        if (!this.isMovingTowards(target, player, this.secondMaxAngle.getValue())) {
            return true;
        }
        if (!this.isMovingTowards(player, target, this.secondMaxAngle.getValue())) {
            return true;
        }
        this.fixMotion();
        return false;
    }

    private boolean prioritizeCriticalHits(EntityLivingBase player) {
        if (player.onGround) {
            return true;
        }
        if (player.hurtTime != 0) {
            return true;
        }
        if (player.fallDistance > this.critFallThreshold.getValue()) {
            return true;
        }
        this.fixMotion();
        return false;
    }

    private boolean prioritizeWTapHits(EntityLivingBase player, boolean sprinting) {
        if (player.isCollidedHorizontally) {
            return true;
        }
        if (!mc.gameSettings.keyBindForward.isKeyDown()) {
            return true;
        }
        if (sprinting) {
            return true;
        }
        this.fixMotion();
        return false;
    }

    private void fixMotion() {
        if (this.set) {
            return;
        }
        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }
        try {
            this.savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
            if (!keepSprint.isEnabled()) {
                keepSprint.toggle();
            }
            keepSprint.slowdown.setValue(0.0F);
            this.set = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resetMotion() {
        if (!this.set) {
            return;
        }
        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }
        try {
            keepSprint.slowdown.setValue((float) this.savedSlowdown);
            if (keepSprint.isEnabled()) {
                keepSprint.toggle();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.set = false;
        this.savedSlowdown = 0.0;
    }

    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngle) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();
        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);
        if (movementLength == 0.0) {
            return false;
        }
        mx /= movementLength;
        mz /= movementLength;
        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);
        if (targetLength == 0.0) {
            return false;
        }
        tx /= targetLength;
        tz /= targetLength;
        double dotProduct = mx * tx + mz * tz;
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
