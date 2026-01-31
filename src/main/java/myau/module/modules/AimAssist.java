package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.ItemUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.util.Vec3;

public class AimAssist extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // Core Settings
    public final FloatProperty range = new FloatProperty("Range", 4.5F, 3.0F, 6.0F);
    public final IntProperty fov = new IntProperty("FOV", 90, 30, 180);
    public final FloatProperty speed = new FloatProperty("Speed", 3.0F, 1.0F, 10.0F);
    public final FloatProperty smoothing = new FloatProperty("Smoothing", 50.0F, 0.0F, 95.0F);

    // Filters
    public final BooleanProperty teamCheck = new BooleanProperty("Team Check", true);
    public final BooleanProperty wallCheck = new BooleanProperty("Wall Check", true);
    public final BooleanProperty weaponOnly = new BooleanProperty("Weapon Only", false);
    public final BooleanProperty onlyOnClick = new BooleanProperty("Only On Click", false);

    // State
    private EntityPlayer target;
    private float velocityYaw;
    private float velocityPitch;
    private int worldId;

    public AimAssist() {
        super("AimAssist", false);
    }

    @Override
    public void onEnabled() {  // FIXED: was onEnable()
        reset();
    }

    @Override
    public void onDisabled() {  // FIXED: was onDisable()
        reset();
    }

    private void reset() {
        target = null;
        velocityYaw = 0.0F;
        velocityPitch = 0.0F;
        worldId = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.POST) {
            return;
        }

        if (mc.thePlayer == null || mc.theWorld == null) {
            reset();
            return;
        }

        // World change detection
        int currentWorldId = mc.theWorld.hashCode();
        if (currentWorldId != worldId) {
            reset();
            worldId = currentWorldId;
            return;
        }

        if (!canAim()) {
            decayVelocity(0.7F);
            target = null;
            return;
        }

        // Validate current target before searching
        if (target != null && !isStillValidTarget(target)) {
            target = null;
        }

        // Find target
        EntityPlayer newTarget = findTarget();

        // Sticky targeting - keep current if still valid and new isn't significantly better
        if (target != null && newTarget != null && newTarget != target) {
            float currentAngle = getAngleToPlayer(target);
            float newAngle = getAngleToPlayer(newTarget);

            // Only switch if new target is significantly closer to crosshair
            if (newAngle < currentAngle * 0.7F) {
                target = newTarget;
            }
        } else {
            target = newTarget;
        }

        if (target == null) {
            decayVelocity(0.8F);
            return;
        }

        aim(target);
    }

    private void decayVelocity(float factor) {
        velocityYaw *= factor;
        velocityPitch *= factor;

        // Zero out tiny values to prevent drift
        if (Math.abs(velocityYaw) < 0.01F) {
            velocityYaw = 0.0F;
        }
        if (Math.abs(velocityPitch) < 0.01F) {
            velocityPitch = 0.0F;
        }
    }

    private boolean canAim() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }

        if (mc.currentScreen != null) {
            return false;
        }

        if (mc.thePlayer.isSpectator()) {
            return false;
        }

        if (weaponOnly.getValue() && !isHoldingWeapon()) {
            return false;
        }

        if (onlyOnClick.getValue() && !mc.gameSettings.keyBindAttack.isKeyDown()) {
            return false;
        }

        // Don't aim when mining blocks
        if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
            if (mc.gameSettings.keyBindAttack.isKeyDown()) {
                return false;
            }
        }

        return true;
    }

    private boolean isHoldingWeapon() {
        if (mc.thePlayer.getHeldItem() == null) {
            return false;
        }

        return mc.thePlayer.getHeldItem().getItem() instanceof ItemSword
                || mc.thePlayer.getHeldItem().getItem() instanceof ItemAxe;
    }

    private boolean isStillValidTarget(EntityPlayer player) {
        if (!isValidTarget(player)) {
            return false;
        }

        float rangeValue = range.getValue();
        float fovValue = fov.getValue();

        double distance = mc.thePlayer.getDistanceToEntity(player);
        if (distance > rangeValue * 1.1F || distance < 0.5) {
            return false;
        }

        float angle = getAngleToPlayer(player);
        if (angle > fovValue * 1.3F) {
            return false;
        }

        if (wallCheck.getValue() && !canSee(player)) {
            return false;
        }

        return true;
    }

    private EntityPlayer findTarget() {
        EntityPlayer best = null;
        float bestScore = Float.MAX_VALUE;

        float rangeValue = range.getValue();
        float fovValue = fov.getValue();

        for (int i = 0; i < mc.theWorld.playerEntities.size(); i++) {
            EntityPlayer player = mc.theWorld.playerEntities.get(i);

            if (!isValidTarget(player)) {
                continue;
            }

            double distance = mc.thePlayer.getDistanceToEntity(player);

            // Range check first
            if (distance > rangeValue || distance < 0.5) {
                continue;
            }

            // FOV check
            float angle = getAngleToPlayer(player);
            if (angle > fovValue) {
                continue;
            }

            // Wall check last (expensive)
            if (wallCheck.getValue() && !canSee(player)) {
                continue;
            }

            // Normalized scoring
            float normalizedAngle = angle / fovValue;
            float normalizedDist = (float) (distance / rangeValue);
            float score = normalizedAngle * 0.7F + normalizedDist * 0.3F;

            if (score < bestScore) {
                bestScore = score;
                best = player;
            }
        }

        return best;
    }

    private boolean isValidTarget(EntityPlayer player) {
        if (player == null || player == mc.thePlayer) {
            return false;
        }

        if (player.isDead || player.getHealth() <= 0) {
            return false;
        }

        if (!player.isEntityAlive()) {
            return false;
        }

        if (player.isSpectator()) {
            return false;
        }

        if (teamCheck.getValue() && TeamUtil.isSameTeam(player)) {
            return false;
        }

        return true;
    }

    private float getAngleToPlayer(EntityPlayer player) {
        Vec3 eyePos = mc.thePlayer.getPositionEyes(1.0F);

        double dx = player.posX - eyePos.xCoord;
        double dy = (player.posY + player.getEyeHeight()) - eyePos.yCoord;
        double dz = player.posZ - eyePos.zCoord;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - mc.thePlayer.rotationYaw));
        float pitchDiff = Math.abs(targetPitch - mc.thePlayer.rotationPitch);

        // 3D angle approximation
        return (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }

    private boolean canSee(EntityPlayer player) {
        Vec3 eyePos = mc.thePlayer.getPositionEyes(1.0F);

        // Check eye level
        Vec3 targetEye = new Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        if (mc.theWorld.rayTraceBlocks(eyePos, targetEye, false, true, false) == null) {
            return true;
        }

        // Check body center
        Vec3 targetBody = new Vec3(player.posX, player.posY + player.height * 0.5, player.posZ);
        return mc.theWorld.rayTraceBlocks(eyePos, targetBody, false, true, false) == null;
    }

    private void aim(EntityPlayer target) {
        // Prediction based on target velocity
        double velX = target.posX - target.lastTickPosX;
        double velZ = target.posZ - target.lastTickPosZ;

        double targetX = target.posX + velX;
        double targetY = target.posY + target.getEyeHeight() * 0.85;
        double targetZ = target.posZ + velZ;

        Vec3 eyePos = mc.thePlayer.getPositionEyes(1.0F);

        double dx = targetX - eyePos.xCoord;
        double dy = targetY - eyePos.yCoord;
        double dz = targetZ - eyePos.zCoord;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        // Calculate deltas
        float yawDelta = MathHelper.wrapAngleTo180_float(targetYaw - mc.thePlayer.rotationYaw);
        float pitchDelta = targetPitch - mc.thePlayer.rotationPitch;

        // Speed and smoothing factors
        float smoothFactor = 1.0F - (smoothing.getValue() / 100.0F);
        float speedMult = speed.getValue() * 0.15F;

        // Calculate target velocities
        float targetVelYaw = yawDelta * smoothFactor * speedMult;
        float targetVelPitch = pitchDelta * smoothFactor * speedMult * 0.8F;

        // Interpolate velocity (no separate decay - this IS the smoothing)
        float lerpFactor = 0.3F + smoothFactor * 0.4F;
        velocityYaw = velocityYaw + (targetVelYaw - velocityYaw) * lerpFactor;
        velocityPitch = velocityPitch + (targetVelPitch - velocityPitch) * lerpFactor;

        // Clamp velocity
        float maxVel = speed.getValue();
        velocityYaw = MathHelper.clamp_float(velocityYaw, -maxVel, maxVel);
        velocityPitch = MathHelper.clamp_float(velocityPitch, -maxVel * 0.8F, maxVel * 0.8F);

        // Validate before applying
        if (Float.isNaN(velocityYaw) || Float.isInfinite(velocityYaw)) {
            velocityYaw = 0.0F;
        }
        if (Float.isNaN(velocityPitch) || Float.isInfinite(velocityPitch)) {
            velocityPitch = 0.0F;
        }

        // Apply directly to player rotations
        mc.thePlayer.rotationYaw += velocityYaw;
        mc.thePlayer.rotationPitch = MathHelper.clamp_float(
                mc.thePlayer.rotationPitch + velocityPitch, -90.0F, 90.0F
        );
    }

    @Override
    public String[] getSuffix() {  // FIXED: was String, now String[]
        if (target != null && target.isEntityAlive()) {
            return new String[]{target.getName()};
        }
        return new String[0];  // FIXED: return empty array instead of null
    }
}
