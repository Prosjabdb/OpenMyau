package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.Vec3;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"Second", "Criticals", "WTap", "Smart"});
    public final BooleanProperty onlyPlayers = new BooleanProperty("OnlyPlayers", true);
    public final BooleanProperty requireSprint = new BooleanProperty("RequireSprint", false);
    
    private boolean sprintState;
    private boolean set;
    private double savedSlowdown;
    private int blockedHits;
    private int allowedHits;
    private long lastAttackTime;
    private int combo;

    public HitSelect() {
        super("HitSelect", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled() || event.getType() != EventType.POST) return;
        
        resetMotion();
        
        // Reset combo if too much time passed
        if (System.currentTimeMillis() - lastAttackTime > 1000) {
            combo = 0;
        }
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
        if (onlyPlayers.getValue() && !(target instanceof EntityPlayer)) return;

        EntityLivingBase living = (EntityLivingBase) target;
        lastAttackTime = System.currentTimeMillis();
        combo++;
        
        boolean allow = shouldAllowHit(living);

        if (!allow) {
            event.setCancelled(true);
            blockedHits++;
        } else {
            allowedHits++;
        }
    }

    private boolean shouldAllowHit(EntityLivingBase target) {
        if (requireSprint.getValue() && !sprintState) return true;
        
        switch (mode.getValue()) {
            case 0: return checkSecondHit(target);
            case 1: return checkCriticalHit();
            case 2: return checkWTapHit();
            case 3: return checkSmartHit(target);
            default: return true;
        }
    }

    private boolean checkSecondHit(EntityLivingBase target) {
        // Always allow if target already hurt (second hit)
        if (target.hurtTime > 0) return true;
        
        // Emergency conditions - always hit
        if (mc.thePlayer.hurtTime > 0) return true;
        if (mc.thePlayer.getDistanceToEntity(target) < 2.0) return true;
        if (combo > 3) return true; // Don't block too many in a row
        
        // Block first hit
        fixMotion();
        return false;
    }

    private boolean checkCriticalHit() {
        // Allow if already critting
        if (!mc.thePlayer.onGround && mc.thePlayer.fallDistance > 0.0f) return true;
        
        // Emergency conditions
        if (mc.thePlayer.hurtTime > 0) return true;
        if (combo > 2) return true;
        
        // Block non-crits
        if (mc.thePlayer.onGround) {
            fixMotion();
            return false;
        }
        
        return true;
    }

    private boolean checkWTapHit() {
        // Allow if sprinting and moving forward
        if (sprintState && mc.gameSettings.keyBindForward.isKeyDown()) return true;
        
        // Emergency conditions
        if (mc.thePlayer.isCollidedHorizontally) return true;
        if (mc.thePlayer.hurtTime > 0) return true;
        if (combo > 2) return true;
        
        // Block bad hits
        fixMotion();
        return false;
    }

    private boolean checkSmartHit(EntityLivingBase target) {
        // Combine Second + Criticals logic
        boolean secondHit = target.hurtTime > 0;
        boolean critting = !mc.thePlayer.onGround && mc.thePlayer.fallDistance > 0.0f;
        
        // Always allow second hit crits
        if (secondHit && critting) return true;
        
        // Allow second hits if combo is high
        if (secondHit && combo > 2) return true;
        
        // Allow crits if health is low (emergency)
        if (critting && mc.thePlayer.getHealth() < 8.0f) return true;
        
        // Emergency - being attacked
        if (mc.thePlayer.hurtTime > 0) return true;
        
        // Too close - just hit
        if (mc.thePlayer.getDistanceToEntity(target) < 2.0) return true;
        
        // Block bad hits
        fixMotion();
        return false;
    }

    private void fixMotion() {
        if (set) return;

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) return;

        savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
        
        if (!keepSprint.isEnabled()) keepSprint.toggle();
        keepSprint.slowdown.setValue(0.0);
        
        set = true;
    }

    private void resetMotion() {
        if (!set) return;

        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint != null) {
            keepSprint.slowdown.setValue(savedSlowdown);
            if (keepSprint.isEnabled()) keepSprint.toggle();
        }

        set = false;
        savedSlowdown = 0.0;
    }

    @Override
    public void onDisabled() {
        resetMotion();
        sprintState = false;
        set = false;
        savedSlowdown = 0.0;
        blockedHits = 0;
        allowedHits = 0;
        combo = 0;
        lastAttackTime = 0;
    }

    @Override
    public String[] getSuffix() {
        String[] modes = {"Second", "Criticals", "WTap", "Smart"};
        return new String[]{modes[mode.getValue()]};
    }
}
