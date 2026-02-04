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
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.Vec3;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"Second", "Criticals", "WTap"});
    
    private boolean sprintState;
    private boolean set;
    private double savedSlowdown;
    private int blockedHits;
    private int allowedHits;

    public HitSelect() {
        super("HitSelect", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled() || event.getType() != EventType.POST) return;
        resetMotion();
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.isCancelled() || event.getType() != EventType.SEND) return;

        if (event.getPacket() instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction packet = (C0BPacketEntityAction) event.getPacket();
            if (packet.getAction() == C0BPacketEntityAction.Action.START_SPRINTING) {
                sprintState = true;
            } else if (packet.getAction() == C0BPacketEntityAction.Action.STOP_SPRINTING) {
                sprintState = false;
            }
            return;
        }

        if (!(event.getPacket() instanceof C02PacketUseEntity)) return;
        
        C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
        if (use.getAction() != C02PacketUseEntity.Action.ATTACK) return;

        Entity target = use.getEntityFromWorld(mc.theWorld);
        if (!(target instanceof EntityLivingBase) || target instanceof EntityLargeFireball) return;

        EntityLivingBase living = (EntityLivingBase) target;
        boolean allow = true;

        switch (mode.getValue()) {
            case 0: allow = prioritizeSecondHit(mc.thePlayer, living); break;
            case 1: allow = prioritizeCriticalHits(mc.thePlayer); break;
            case 2: allow = prioritizeWTapHits(mc.thePlayer, sprintState); break;
        }

        if (!allow) {
            event.setCancelled(true);
            blockedHits++;
        } else {
            allowedHits++;
        }
    }

    private boolean prioritizeSecondHit(EntityLivingBase player, EntityLivingBase target) {
        if (target.hurtTime != 0) return true;
        if (player.hurtTime <= player.maxHurtTime - 1) return true;
        if (player.getDistanceToEntity(target) < 2.5) return true;
        if (!isMovingTowards(target, player, 60.0)) return true;
        if (!isMovingTowards(player, target, 60.0)) return true;

        fixMotion();
        return false;
    }

    private boolean prioritizeCriticalHits(EntityLivingBase player) {
        if (player.onGround) return true;
        if (player.hurtTime != 0) return true;
        if (player.fallDistance > 0.0f) return true;

        fixMotion();
        return false;
    }

    private boolean prioritizeWTapHits(EntityLivingBase player, boolean sprinting) {
        if (player.isCollidedHorizontally) return true;
        if (!mc.gameSettings.keyBindForward.isKeyDown()) return true;
        if (sprinting) return true;

        fixMotion();
        return false;
    }

    private void fixMotion() {
        if (set) return;

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) return;

        savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
        
        if (!keepSprint.isEnabled()) keepSprint.toggle();
        keepSprint.slowdown.setValue(0);
        
        set = true;
    }

    private void resetMotion() {
        if (!set) return;

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint != null) {
            keepSprint.slowdown.setValue((int) savedSlowdown);
            if (keepSprint.isEnabled()) keepSprint.toggle();
        }

        set = false;
        savedSlowdown = 0.0;
    }

    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngle) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();

        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double moveLen = Math.sqrt(mx * mx + mz * mz);
        if (moveLen == 0.0) return false;

        mx /= moveLen;
        mz /= moveLen;

        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLen = Math.sqrt(tx * tx + tz * tz);
        if (targetLen == 0.0) return false;

        tx /= targetLen;
        tz /= targetLen;

        double dot = mx * tx + mz * tz;
        return dot >= Math.cos(Math.toRadians(maxAngle));
    }

    @Override
    public void onDisabled() {
        resetMotion();
        sprintState = false;
        set = false;
        savedSlowdown = 0.0;
        blockedHits = 0;
        allowedHits = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}
