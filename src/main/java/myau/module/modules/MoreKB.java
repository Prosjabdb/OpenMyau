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

    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{
            "LEGIT", "LEGIT_FAST", "LESS_PACKET", "PACKET", "DOUBLE_PACKET"
    });

    public final BooleanProperty intelligent = new BooleanProperty("Intelligent", false);
    public final BooleanProperty onlyGround = new BooleanProperty("Only Ground", true);

    private boolean sprintResetPending;
    private EntityLivingBase currentTarget;

    public MoreKB() {
        super("MoreKB", false);
        this.sprintResetPending = false;
        this.currentTarget = null;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) return;

        Entity target = event.getTarget();
        if (target instanceof EntityLivingBase) {
            this.currentTarget = (EntityLivingBase) target;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) return;

        switch (mode.getValue()) {
            case 1: // LEGIT_FAST
                handleLegitFast();
                return;
        }

        EntityLivingBase entity = getTargetFromCrosshair();
        if (entity == null) return;

        if (intelligent.getValue() && !isFacingTarget(entity, 120.0F)) return;

        if (entity.hurtTime == 10) {
            switch (mode.getValue()) {
                case 0: // LEGIT
                    handleLegitSprintReset();
                    break;
                case 2: // LESS_PACKET
                    handleLessPacketSprint();
                    break;
                case 3: // PACKET
                    handlePacketSprint();
                    break;
                case 4: // DOUBLE_PACKET
                    handleDoublePacketSprint();
                    break;
            }
        }
    }

    private void handleLegitFast() {
        if (currentTarget != null && isPlayerMoving()) {
            if (!onlyGround.getValue() || mc.thePlayer.onGround) {
                mc.thePlayer.sprintingTicksLeft = 0;
            }
            currentTarget = null;
        }
    }

    private EntityLivingBase getTargetFromCrosshair() {
        if (mc.objectMouseOver == null || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) return null;

        Entity entity = mc.objectMouseOver.entityHit;
        if (entity instanceof EntityLivingBase) {
            return (EntityLivingBase) entity;
        }
        return null;
    }

    private boolean isFacingTarget(EntityLivingBase entity, float maxAngle) {
        double dx = mc.thePlayer.posX - entity.posX;
        double dz = mc.thePlayer.posZ - entity.posZ;
        float yawToTarget = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI - 90.0);
        float diffYaw = Math.abs(MathHelper.wrapAngleTo180_float(yawToTarget - entity.rotationYawHead));
        return diffYaw <= maxAngle;
    }

    private boolean isPlayerMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }

    private void handleLegitSprintReset() {
        sprintResetPending = true;
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
            mc.thePlayer.setSprinting(true);
        }
        sprintResetPending = false;
    }

    private void handleLessPacketSprint() {
        if (mc.thePlayer.isSprinting()) mc.thePlayer.setSprinting(false);
        mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
        mc.thePlayer.setSprinting(true);
    }

    private void handlePacketSprint() {
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
        mc.thePlayer.setSprinting(true);
    }

    private void handleDoublePacketSprint() {
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
        mc.thePlayer.setSprinting(true);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}
