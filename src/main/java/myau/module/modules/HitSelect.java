package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import myau.property.properties.NumberProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.Vec3;
import net.minecraft.util.ChatComponentText;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"SECOND", "CRITICALS", "W_TAP"});
    public final NumberProperty wtapDelay = new NumberProperty("WTap Delay", 5, 1, 20, 1);
    public final NumberProperty critHeight = new NumberProperty("Critical Height", 0.1, 0.0, 1.0, 0.01);
    public final NumberProperty blockChanceProp = new NumberProperty("Block Chance", 80, 0, 100, 1); // %

    private boolean sprintState = false;
    private boolean set = false;
    private double savedSlowdown = 0.0;
    private int blockedHits = 0;
    private int allowedHits = 0;
    private int tickReset = 0;

    public HitSelect() {
        super("HitSelect", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        if (event.getType() == EventType.POST) {
            this.resetMotion();
            tickReset++;
            if (tickReset >= 20) { // auto-reset KeepSprint every second
                this.resetMotion();
                tickReset = 0;
            }

            if (blockedHits > 0 || allowedHits > 0) {
                mc.thePlayer.addChatMessage(
                        new ChatComponentText("HitSelect: Blocked " + blockedHits + ", Allowed " + allowedHits)
                );
                blockedHits = 0;
                allowedHits = 0;
            }
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) return;

        if (event.getPacket() instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction packet = (C0BPacketEntityAction) event.getPacket();
            switch (packet.getAction()) {
                case START_SPRINTING:
                    sprintState = true;
                    break;
                case STOP_SPRINTING:
                    sprintState = false;
                    break;
            }
            return;
        }

        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) return;

            Entity target = use.getEntityFromWorld(mc.theWorld);
            if (target == null || target instanceof EntityLargeFireball) return;
            if (!(target instanceof EntityLivingBase)) return;

            EntityLivingBase living = (EntityLivingBase) target;
            boolean allow = true;

            switch (mode.getValue()) {
                case 0: // SECOND
                    allow = prioritizeSecondHit(mc.thePlayer, living);
                    break;
                case 1: // CRITICALS
                    allow = prioritizeCriticalHits(mc.thePlayer);
                    break;
                case 2: // W_TAP
                    allow = prioritizeWTapHits(mc.thePlayer, sprintState);
                    break;
            }

            double chance = blockChanceProp.getValue().doubleValue() / 100.0;
            if (!allow && Math.random() < chance) {
                event.setCancelled(true);
                blockedHits++;
            } else {
                allowedHits++;
            }
        }
    }

    private boolean prioritizeSecondHit(EntityLivingBase player, EntityLivingBase target) {
        if (target.hurtTime != 0 || player.hurtTime <= player.maxHurtTime - 1) return true;
        if (player.getDistanceToEntity(target) < 2.5) return true;
        if (!isMovingTowards(target, player, 60.0)) return true;
        if (!isMovingTowards(player, target, 60.0)) return true;
        fixMotion();
        return false;
    }

    private boolean prioritizeCriticalHits(EntityLivingBase player) {
        if (player.onGround || player.hurtTime != 0 || player.fallDistance > critHeight.getValue().floatValue()) return true;
        doCriticalJump();
        fixMotion();
        return false;
    }

    private boolean prioritizeWTapHits(EntityLivingBase player, boolean sprinting) {
        if (player.isCollidedHorizontally) return true;
        if (!mc.gameSettings.keyBindForward.isKeyDown()) return true;
        if (sprinting) return true;
        if (tickReset < wtapDelay.getValue().intValue()) return true;
        fixMotion();
        return false;
    }

    private void fixMotion() {
        if (set) return;

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) return;

        try {
            savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
            if (!keepSprint.isEnabled()) keepSprint.toggle();
            keepSprint.slowdown.setValue(0);
            spoofSprint();
            set = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resetMotion() {
        if (!set) return;

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) return;

        try {
            keepSprint.slowdown.setValue((int) savedSlowdown);
            if (keepSprint.isEnabled()) keepSprint.toggle();
        } catch (Exception e) {
            e.printStackTrace();
        }

        set = false;
        savedSlowdown = 0.0;
    }

    private void doCriticalJump() {
        if (mc.thePlayer.onGround) {
            mc.thePlayer.motionY = 0.1 + Math.random() * 0.05;
        }
    }

    private void spoofSprint() {
        if (!sprintState && mc.thePlayer.isSprinting()) sprintState = true;
        mc.thePlayer.setSprinting(true);
    }

    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngle) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();

        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);
        if (movementLength == 0.0) return false;
        mx /= movementLength;
        mz /= movementLength;

        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);
        if (targetLength == 0.0) return false;
        tx /= targetLength;
        tz /= targetLength;

        double dotProduct = mx * tx + mz * tz;
        return dotProduct >= Math.cos(Math.toRadians(maxAngle));
    }

    @Override
    public void onDisabled() {
        resetMotion();
        sprintState = false;
        set = false;
        savedSlowdown = 0.0;
        blockedHits = 0;
        allowedHits = 0;
        tickReset = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}
