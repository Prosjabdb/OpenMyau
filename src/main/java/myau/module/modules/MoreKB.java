package myau.module.modules;

import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

public class MoreKB extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"Legit", "LegitFast", "LessPacket", "Packet", "DoublePacket"});
    public final BooleanProperty intelligent = new BooleanProperty("Intelligent", false);
    public final BooleanProperty onlyGround = new BooleanProperty("OnlyGround", true);
    
    private boolean shouldSprintReset;
    private EntityLivingBase target;

    public MoreKB() {
        super("MoreKB", false);
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!isEnabled()) return;
        
        Entity entity = event.getTarget();
        if (entity instanceof EntityLivingBase) {
            this.target = (EntityLivingBase) entity;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled()) return;
        
        if (mode.getValue() == 1) {
            handleLegitFast();
            return;
        }
        
        EntityLivingBase entity = getTargetEntity();
        if (entity == null) return;
        if (!isValidAngle(entity)) return;
        if (entity.hurtTime != 10) return;
        
        executeKBMode();
    }
    
    private void handleLegitFast() {
        if (target == null || !isMoving()) return;
        
        if (!onlyGround.getValue() || mc.thePlayer.onGround) {
            mc.thePlayer.sprintingTicksLeft = 0;
        }
        target = null;
    }
    
    private EntityLivingBase getTargetEntity() {
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) return null;
        if (!(mop.entityHit instanceof EntityLivingBase)) return null;
        return (EntityLivingBase) mop.entityHit;
    }
    
    private boolean isValidAngle(EntityLivingBase entity) {
        if (!intelligent.getValue()) return true;
        
        double x = mc.thePlayer.posX - entity.posX;
        double z = mc.thePlayer.posZ - entity.posZ;
        float calcYaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI - 90.0);
        float diffY = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - entity.rotationYawHead));
        
        return diffY <= 120.0F;
    }
    
    private void executeKBMode() {
        switch (mode.getValue()) {
            case 0:
                handleLegit();
                break;
            case 2:
                handleLessPacket();
                break;
            case 3:
                sendSprintPacket(true);
                break;
            case 4:
                sendSprintPacket(true);
                sendSprintPacket(true);
                break;
        }
    }
    
    private void handleLegit() {
        shouldSprintReset = true;
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
            mc.thePlayer.setSprinting(true);
        }
        shouldSprintReset = false;
    }
    
    private void handleLessPacket() {
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
        }
        mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
        mc.thePlayer.setSprinting(true);
    }
    
    private void sendSprintPacket(boolean start) {
        C0BPacketEntityAction.Action action = start ? 
            C0BPacketEntityAction.Action.START_SPRINTING : 
            C0BPacketEntityAction.Action.STOP_SPRINTING;
        
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, action));
        
        if (start) {
            mc.thePlayer.setSprinting(true);
        }
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModes()[mode.getValue()]};
    }
}
