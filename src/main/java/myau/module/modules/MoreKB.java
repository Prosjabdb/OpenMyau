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

    private static final float INTELLIGENT_ANGLE_THRESHOLD = 120.0f;
    private static final int FRESH_HIT_HURTTIME = 10;

    public final ModeProperty mode = new ModeProperty("mode", 0,
            new String[]{"LEGIT", "LEGIT_FAST", "LESS_PACKET", "PACKET", "DOUBLE_PACKET"});
    public final BooleanProperty intelligent = new BooleanProperty("intelligent", false);
    public final BooleanProperty onlyGround = new BooleanProperty("only-ground", true);
    public final BooleanProperty onlySprinting = new BooleanProperty("only-sprinting", true);
    public final BooleanProperty onlyMoving = new BooleanProperty("only-moving", true);

    private boolean shouldSprintReset;
    private int lastTargetId = -1;
    private int lastTargetHurtTime;
    private boolean didResetThisHit;

    public MoreKB() {
        super("MoreKB", false);
    }

    @Override
    public void onEnabled() {
        this.shouldSprintReset = false;
        this.lastTargetId = -1;
        this.lastTargetHurtTime = 0;
        this.didResetThisHit = false;
    }

    @Override
    public void onDisabled() {
        this.shouldSprintReset = false;
        this.lastTargetId = -1;
        this.lastTargetHurtTime = 0;
        this.didResetThisHit = false;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) return;

        Entity targetEntity = event.getTarget();
        if (!(targetEntity instanceof EntityLivingBase)) return;

        EntityLivingBase living = (EntityLivingBase) targetEntity;
        int id = living.getEntityId();

        // new target or target recovered from last hit
        if (id != this.lastTargetId) {
            this.lastTargetId = id;
            this.didResetThisHit = false;
        }

        // LEGIT_FAST: reset sprint ticks on attack frame
        if (this.mode.getValue() == 1) {
            if (!this.canPerform()) return;
            mc.thePlayer.sprintingTicksLeft = 0;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        // LEGIT_FAST handled in onAttack
        if (this.mode.getValue() == 1) return;

        EntityLivingBase target = this.getTarget();
        if (target == null) return;

        // track hurtTime transitions to avoid repeating on same hit
        int hurtTime = target.hurtTime;
        int targetId = target.getEntityId();

        if (targetId != this.lastTargetId) {
            this.lastTargetId = targetId;
            this.didResetThisHit = false;
        }

        // detect the exact tick the hit lands (hurtTime jumps to max)
        boolean freshHit = hurtTime == FRESH_HIT_HURTTIME
                && this.lastTargetHurtTime < hurtTime
                && !this.didResetThisHit;

        this.lastTargetHurtTime = hurtTime;

        if (!freshHit) return;
        if (!this.canPerform()) return;

        // intelligent: skip if target is facing away (running, not fighting back)
        if (this.intelligent.getValue() && !this.isTargetFacingUs(target)) return;

        this.performReset();
        this.didResetThisHit = true;
    }

    /**
     * Check all the "should we even try" conditions in one place.
     */
    private boolean canPerform() {
        if (mc.thePlayer == null) return false;
        if (this.onlyGround.getValue() && !mc.thePlayer.onGround) return false;
        if (this.onlySprinting.getValue() && !mc.thePlayer.isSprinting()) return false;
        if (this.onlyMoving.getValue() && !this.isMoving()) return false;
        return true;
    }

    /**
     * Gets the entity we're currently looking at, if it's a valid living target.
     */
    private EntityLivingBase getTarget() {
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) return null;
        if (!(mop.entityHit instanceof EntityLivingBase)) return null;
        return (EntityLivingBase) mop.entityHit;
    }

    /**
     * Check if the target is roughly facing towards us (within 120° of looking at us).
     * Used for intelligent mode — no point sprint resetting on someone running away,
     * they already get full KB.
     */
    private boolean isTargetFacingUs(EntityLivingBase target) {
        double dx = mc.thePlayer.posX - target.posX;
        double dz = mc.thePlayer.posZ - target.posZ;
        float angleToUs = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float diff = Math.abs(MathHelper.wrapAngleTo180_float(angleToUs - target.rotationYawHead));
        return diff <= INTELLIGENT_ANGLE_THRESHOLD;
    }

    /**
     * Execute the actual sprint reset based on current mode.
     */
    private void performReset() {
        switch (this.mode.getValue()) {
            case 0: // LEGIT — client-side sprint toggle
                this.shouldSprintReset = true;
                mc.thePlayer.setSprinting(false);
                mc.thePlayer.setSprinting(true);
                this.shouldSprintReset = false;
                break;

            case 2: // LESS_PACKET — unsprint client, send start packet
                mc.thePlayer.setSprinting(false);
                this.sendSprintPacket(true);
                mc.thePlayer.setSprinting(true);
                break;

            case 3: // PACKET — stop + start packets
                this.sendSprintPacket(false);
                this.sendSprintPacket(true);
                mc.thePlayer.setSprinting(true);
                break;

            case 4: // DOUBLE_PACKET — double stop + start cycle
                this.sendSprintPacket(false);
                this.sendSprintPacket(true);
                this.sendSprintPacket(false);
                this.sendSprintPacket(true);
                mc.thePlayer.setSprinting(true);
                break;
        }
    }

    private void sendSprintPacket(boolean start) {
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer,
                start ? C0BPacketEntityAction.Action.START_SPRINTING
                        : C0BPacketEntityAction.Action.STOP_SPRINTING));
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0f || mc.thePlayer.moveStrafing != 0.0f;
    }

    public boolean isShouldSprintReset() {
        return this.shouldSprintReset;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
