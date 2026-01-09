package myau.module.modules;

import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

public class MoreKB extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"LEGIT", "LEGIT_FAST", "LESS_PACKET", "PACKET", "DOUBLE_PACKET"});
    public final BooleanProperty intelligent = new BooleanProperty("Intelligent", false);
    public final BooleanProperty onlyGround = new BooleanProperty("Only Ground", true);
    public final FloatProperty velocityThreshold = new FloatProperty("Velocity Threshold", 0.1F, 0.0F, 1.0F);
    public final FloatProperty randomization = new FloatProperty("Randomization", 0.1F, 0.0F, 0.5F);
    public final IntProperty delayTicks = new IntProperty("Delay Ticks", 10, 0, 50);
    public final BooleanProperty checkPotions = new BooleanProperty("Check Potions", true);
    private boolean shouldSprintReset;
    private EntityLivingBase target;
    private long lastActivation = 0L;

    public MoreKB() {
        super("MoreKB", false);
        this.shouldSprintReset = false;
        this.target = null;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Entity targetEntity = event.getTarget();
        if (targetEntity != null && targetEntity instanceof EntityLivingBase) {
            this.target = (EntityLivingBase) targetEntity;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        if (currentTime - this.lastActivation < this.delayTicks.getValue() * 50L) {
            return; // Enforce delay between activations
        }
        if (this.mode.getValue() == 1) {
            if (this.target != null && this.isMoving() && (this.onlyGround.getValue() ? mc.thePlayer.onGround : true)) {
                mc.thePlayer.sprintingTicksLeft = 0;
            }
            this.target = null;
            return;
        }
        EntityLivingBase entity = null;
        if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            entity = (EntityLivingBase) mc.objectMouseOver.entityHit;
        }
        if (entity == null || entity == mc.thePlayer) {
            return;
        }
        double x = mc.thePlayer.posX - entity.posX;
        double z = mc.thePlayer.posZ - entity.posZ;
        float calcYaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI - 90.0);
        float diffY = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - entity.rotationYawHead));
        if (this.intelligent.getValue() && diffY > 120.0F) {
            return;
        }
        double entityVelocity = Math.sqrt(entity.motionX * entity.motionX + entity.motionZ * entity.motionZ);
        if (entityVelocity < this.velocityThreshold.getValue()) {
            return;
        }
        if (this.checkPotions.getValue() && mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
            // Adjust for speed potions to avoid over-triggering
            if (entityVelocity < this.velocityThreshold.getValue() * 1.5) {
                return;
            }
        }
        if (entity.hurtTime == 10) {
            this.lastActivation = currentTime;
            float randomOffset = (float) (Math.random() * this.randomization.getValue() - this.randomization.getValue() / 2);
            switch (this.mode.getValue()) {
                case 0: // LEGIT
                    this.shouldSprintReset = true;
                    if (mc.thePlayer.isSprinting()) {
                        mc.thePlayer.setSprinting(false);
                        try {
                            Thread.sleep((long) (50 + randomOffset * 100)); // Slight delay with randomization
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        mc.thePlayer.setSprinting(true);
                    }
                    this.shouldSprintReset = false;
                    break;
                case 2: // LESS_PACKET
                    if (mc.thePlayer.isSprinting()) {
                        mc.thePlayer.setSprinting(false);
                    }
                    mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.setSprinting(true);
                    break;
                case 3: // PACKET
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.setSprinting(true);
                    break;
                case 4: // DOUBLE_PACKET
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.setSprinting(true);
                    break;
            }
        }
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
