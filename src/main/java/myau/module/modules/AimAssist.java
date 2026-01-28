package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.KeyEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.util.*;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.util.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Advanced AimAssist Module
 * Provides human-like, intelligent targeting with prediction and adaptive behavior
 * 
 * Features:
 * - Smooth, natural aiming with randomization
 * - Multiple target selection modes (adaptive, closest, health, angle)
 * - Target prediction for moving players
 * - Hitbox-specific targeting (head, center, feet)
 * - Adaptive smoothing based on distance
 * - Target locking (stick to one target)
 * - Smart activation controls
 * - Performance optimized
 */
public class ImprovedAimAssist extends Module {
    
    // ==================== Constants ====================
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random random = new Random();
    private static final double PREDICTION_MULTIPLIER = 0.15; // Ticks ahead to predict
    private static final int RANDOMIZATION_CHANCE = 20; // 1 in 20 chance
    
    // ==================== Timing Properties ====================
    public final IntProperty attackDelay = new IntProperty("Attack Delay", 350, 0, 1000);
    
    // ==================== Speed Properties ====================
    public final FloatProperty hSpeed = new FloatProperty("Horizontal Speed", 3.0F, 0.0F, 10.0F);
    public final FloatProperty vSpeed = new FloatProperty("Vertical Speed", 2.5F, 0.0F, 10.0F);
    public final PercentProperty smoothing = new PercentProperty("Smoothing", 50);
    
    // ==================== Adaptive Smoothing ====================
    public final BooleanProperty adaptiveSmoothing = new BooleanProperty("Adaptive Smoothing", true);
    public final FloatProperty closeRangeSmoothing = new FloatProperty("Close Range Smoothing", 80.0F, 0.0F, 100.0F);
    public final FloatProperty farRangeSmoothing = new FloatProperty("Far Range Smoothing", 30.0F, 0.0F, 100.0F);
    public final FloatProperty smoothTransitionDistance = new FloatProperty("Smooth Transition Distance", 3.0F, 1.0F, 6.0F);
    
    // ==================== Range & FOV ====================
    public final FloatProperty range = new FloatProperty("Range", 4.5F, 3.0F, 8.0F);
    public final IntProperty fov = new IntProperty("FOV", 90, 30, 360);
    
    // ==================== Target Selection ====================
    public final ModeProperty targetMode = new ModeProperty(
        "Target Mode",
        "Adaptive",
        "Adaptive", "Closest", "Lowest Health", "Best Angle", "Crosshair"
    );
    
    public final BooleanProperty targetLocking = new BooleanProperty("Target Locking", true);
    public final FloatProperty lockSwitchDistance = new FloatProperty("Lock Switch Distance", 1.5F, 0.5F, 4.0F);
    
    // ==================== Target Prediction ====================
    public final BooleanProperty targetPrediction = new BooleanProperty("Target Prediction", true);
    public final FloatProperty predictionStrength = new FloatProperty("Prediction Strength", 1.0F, 0.0F, 3.0F);
    public final BooleanProperty smartPrediction = new BooleanProperty("Smart Prediction", true);
    
    // ==================== Hitbox Targeting ====================
    public final ModeProperty hitboxMode = new ModeProperty(
        "Hitbox Target",
        "Center",
        "Center", "Head", "Upper Body", "Lower Body", "Feet", "Dynamic"
    );
    
    public final FloatProperty hitboxRandomization = new FloatProperty("Hitbox Randomization", 0.1F, 0.0F, 0.5F);
    
    // ==================== Activation Controls ====================
    public final BooleanProperty onlyWhileAttacking = new BooleanProperty("Only While Attacking", false);
    public final BooleanProperty excludeTools = new BooleanProperty("Exclude Tools", true);
    public final BooleanProperty requireWeapon = new BooleanProperty("Require Weapon", false);
    public final BooleanProperty allowFists = new BooleanProperty("Allow Fists", true);
    public final BooleanProperty allowSticks = new BooleanProperty("Allow Sticks", true);
    
    // ==================== Advanced Filters ====================
    public final BooleanProperty botChecks = new BooleanProperty("Bot Check", true);
    public final BooleanProperty teamCheck = new BooleanProperty("Team Check", true);
    public final BooleanProperty breakOnObstruction = new BooleanProperty("Break On Obstruction", true);
    public final BooleanProperty ignoreInvisible = new BooleanProperty("Ignore Invisible", true);
    
    // ==================== Randomization ====================
    public final BooleanProperty enableRandomization = new BooleanProperty("Enable Randomization", true);
    public final FloatProperty randomStrength = new FloatProperty("Random Strength", 0.3F, 0.0F, 1.0F);
    
    // ==================== Performance ====================
    public final IntProperty updateRate = new IntProperty("Update Rate", 1, 1, 5);
    
    // ==================== State Variables ====================
    private final TimerUtil attackTimer = new TimerUtil();
    private EntityPlayer lockedTarget = null;
    private long lastTargetLockTime = 0L;
    private int tickCounter = 0;
    
    // Smoothing variables
    private float lastYawDelta = 0.0F;
    private float lastPitchDelta = 0.0F;
    
    // Randomization state
    private long nextRandomizationTick = 0L;
    private float randomYawOffset = 0.0F;
    private float randomPitchOffset = 0.0F;
    
    // ==================== Constructor ====================
    public ImprovedAimAssist() {
        super("AimAssist", false);
    }
    
    // ==================== Core Logic ====================
    
    /**
     * Check if player is holding a valid item for aim assist
     */
    private boolean isHoldingValidItem() {
        ItemStack heldItem = mc.thePlayer.getHeldItem();
        
        // Allow fists (empty hand)
        if (heldItem == null) {
            return allowFists.getValue();
        }
        
        // Block swords if require weapon is disabled
        if (heldItem.getItem() instanceof ItemSword) {
            return true;
        }
        
        // Allow sticks
        if (allowSticks.getValue() && heldItem.getUnlocalizedName().contains("stick")) {
            return true;
        }
        
        // Exclude tools
        if (excludeTools.getValue() && ItemUtil.isHoldingTool()) {
            return false;
        }
        
        // Check weapon requirement
        if (requireWeapon.getValue()) {
            return ItemUtil.hasRawUnbreakingEnchant();
        }
        
        return true;
    }
    
    /**
     * Validate if entity is a valid target
     */
    private boolean isValidTarget(EntityPlayer target) {
        // Basic null and self checks
        if (target == null || target == mc.thePlayer || target == mc.thePlayer.ridingEntity) {
            return false;
        }
        
        // Render entity checks
        if (target == mc.getRenderViewEntity() || target == mc.getRenderViewEntity().ridingEntity) {
            return false;
        }
        
        // Death check
        if (target.deathTime > 0) {
            return false;
        }
        
        // Invisibility check
        if (ignoreInvisible.getValue() && target.isInvisible()) {
            return false;
        }
        
        // Range check
        double distance = RotationUtil.distanceToEntity(target);
        if (distance > range.getValue()) {
            return false;
        }
        
        // FOV check
        if (RotationUtil.angleToEntity(target) > fov.getValue()) {
            return false;
        }
        
        // Obstruction check (walls)
        if (breakOnObstruction.getValue() && RotationUtil.rayTrace(target) != null) {
            return false;
        }
        
        // Friend check
        if (TeamUtil.isFriend(target)) {
            return false;
        }
        
        // Team check
        if (teamCheck.getValue() && TeamUtil.isSameTeam(target)) {
            return false;
        }
        
        // Bot check
        if (botChecks.getValue() && TeamUtil.isBot(target)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Check if entity is in reach (considering Reach module)
     */
    private boolean isInReach(EntityPlayer target) {
        Reach reach = (Reach) Myau.moduleManager.modules.get(Reach.class);
        double reachDistance = reach != null && reach.isEnabled() 
            ? reach.range.getValue() 
            : 3.0;
        return RotationUtil.distanceToEntity(target) <= reachDistance;
    }
    
    /**
     * Check if player is looking at a block
     */
    private boolean isLookingAtBlock() {
        return mc.objectMouseOver != null 
            && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK;
    }
    
    /**
     * Get all valid targets sorted by priority mode
     */
    private List<EntityPlayer> getValidTargets() {
        List<EntityPlayer> targets = mc.theWorld.loadedEntityList.stream()
            .filter(entity -> entity instanceof EntityPlayer)
            .map(entity -> (EntityPlayer) entity)
            .filter(this::isValidTarget)
            .collect(Collectors.toList());
        
        // Apply target mode sorting
        String mode = targetMode.getValue();
        
        switch (mode) {
            case "Closest":
                targets.sort(Comparator.comparingDouble(RotationUtil::distanceToEntity));
                break;
                
            case "Lowest Health":
                targets.sort(Comparator.comparingDouble(EntityPlayer::getHealth));
                break;
                
            case "Best Angle":
                targets.sort(Comparator.comparingDouble(RotationUtil::angleToEntity));
                break;
                
            case "Crosshair":
                targets.sort(Comparator.comparingDouble(this::getCrosshairDistance));
                break;
                
            case "Adaptive":
                targets.sort(this::adaptiveTargetComparator);
                break;
                
            default:
                targets.sort(Comparator.comparingDouble(RotationUtil::distanceToEntity));
        }
        
        return targets;
    }
    
    /**
     * Adaptive target selection: balances distance, angle, and health
     */
    private int adaptiveTargetComparator(EntityPlayer a, EntityPlayer b) {
        double distanceA = RotationUtil.distanceToEntity(a);
        double distanceB = RotationUtil.distanceToEntity(b);
        double angleA = RotationUtil.angleToEntity(a);
        double angleB = RotationUtil.angleToEntity(b);
        double healthA = a.getHealth();
        double healthB = b.getHealth();
        
        // Normalize values (0-1 range)
        double maxDist = range.getValue();
        double maxAngle = fov.getValue();
        double maxHealth = 20.0;
        
        double normDistA = distanceA / maxDist;
        double normDistB = distanceB / maxDist;
        double normAngleA = angleA / maxAngle;
        double normAngleB = angleB / maxAngle;
        double normHealthA = healthA / maxHealth;
        double normHealthB = healthB / maxHealth;
        
        // Weighted score (lower is better)
        // Distance: 40%, Angle: 40%, Health: 20%
        double scoreA = (normDistA * 0.4) + (normAngleA * 0.4) + (normHealthA * 0.2);
        double scoreB = (normDistB * 0.4) + (normAngleB * 0.4) + (normHealthB * 0.2);
        
        return Double.compare(scoreA, scoreB);
    }
    
    /**
     * Get distance from crosshair to entity
     */
    private double getCrosshairDistance(EntityPlayer target) {
        float[] rotations = RotationUtil.getRotationsToBox(
            target.getEntityBoundingBox(),
            mc.thePlayer.rotationYaw,
            mc.thePlayer.rotationPitch,
            180.0F,
            0.5F
        );
        
        float yawDiff = Math.abs(rotations[0] - mc.thePlayer.rotationYaw);
        float pitchDiff = Math.abs(rotations[1] - mc.thePlayer.rotationPitch);
        
        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }
    
    /**
     * Select best target with locking logic
     */
    private EntityPlayer selectTarget(List<EntityPlayer> validTargets) {
        if (validTargets.isEmpty()) {
            return null;
        }
        
        // Target locking enabled
        if (targetLocking.getValue() && lockedTarget != null) {
            // Check if locked target is still valid
            if (validTargets.contains(lockedTarget)) {
                double lockDistance = RotationUtil.distanceToEntity(lockedTarget);
                
                // Keep lock unless another target is significantly closer
                EntityPlayer closestTarget = validTargets.get(0);
                double closestDistance = RotationUtil.distanceToEntity(closestTarget);
                
                if (closestDistance < lockDistance - lockSwitchDistance.getValue()) {
                    // Switch to closer target
                    lockedTarget = closestTarget;
                    lastTargetLockTime = System.currentTimeMillis();
                }
                
                return lockedTarget;
            } else {
                // Locked target no longer valid
                lockedTarget = null;
            }
        }
        
        // Select new target
        EntityPlayer newTarget = validTargets.get(0);
        
        if (targetLocking.getValue()) {
            lockedTarget = newTarget;
            lastTargetLockTime = System.currentTimeMillis();
        }
        
        return newTarget;
    }
    
    /**
     * Predict target position based on movement
     */
    private Vec3 predictTargetPosition(EntityPlayer target) {
        if (!targetPrediction.getValue()) {
            return target.getPositionVector();
        }
        
        Vec3 currentPos = target.getPositionVector();
        
        // Calculate velocity
        double motionX = target.posX - target.lastTickPosX;
        double motionY = target.posY - target.lastTickPosY;
        double motionZ = target.posZ - target.lastTickPosZ;
        
        // Smart prediction: only predict if target is moving
        if (smartPrediction.getValue()) {
            double velocity = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
            if (velocity < 0.05) {
                // Target is not moving, no prediction needed
                return currentPos;
            }
        }
        
        // Distance-based prediction strength
        double distance = RotationUtil.distanceToEntity(target);
        double predictionMultiplier = PREDICTION_MULTIPLIER * predictionStrength.getValue();
        
        // Further targets need more prediction
        predictionMultiplier *= (distance / 3.0);
        
        // Predict future position
        return new Vec3(
            currentPos.xCoord + (motionX * predictionMultiplier),
            currentPos.yCoord + (motionY * predictionMultiplier),
            currentPos.zCoord + (motionZ * predictionMultiplier)
        );
    }
    
    /**
     * Get target position on specific hitbox
     */
    private Vec3 getHitboxTarget(EntityPlayer target, Vec3 predictedPos) {
        AxisAlignedBB bb = target.getEntityBoundingBox();
        double collisionBorder = target.getCollisionBorderSize();
        
        // Base position
        double x = predictedPos.xCoord;
        double y = predictedPos.yCoord;
        double z = predictedPos.zCoord;
        
        // Apply hitbox mode
        String mode = hitboxMode.getValue();
        
        switch (mode) {
            case "Head":
                y = bb.maxY - 0.2;
                break;
                
            case "Upper Body":
                y = bb.minY + (bb.maxY - bb.minY) * 0.75;
                break;
                
            case "Center":
                y = bb.minY + (bb.maxY - bb.minY) * 0.5;
                break;
                
            case "Lower Body":
                y = bb.minY + (bb.maxY - bb.minY) * 0.25;
                break;
                
            case "Feet":
                y = bb.minY + 0.2;
                break;
                
            case "Dynamic":
                // Dynamic: aim at center when close, head when far
                double distance = RotationUtil.distanceToEntity(target);
                double ratio = Math.min(distance / range.getValue(), 1.0);
                y = bb.minY + (bb.maxY - bb.minY) * (0.5 + ratio * 0.3);
                break;
        }
        
        // Apply randomization for human-like behavior
        if (enableRandomization.getValue() && hitboxRandomization.getValue() > 0) {
            double randomRange = hitboxRandomization.getValue();
            x += (random.nextDouble() - 0.5) * randomRange;
            y += (random.nextDouble() - 0.5) * randomRange * 0.5; // Less vertical variation
            z += (random.nextDouble() - 0.5) * randomRange;
        }
        
        return new Vec3(x, y, z);
    }
    
    /**
     * Calculate adaptive smoothing based on distance
     */
    private float getAdaptiveSmoothing(EntityPlayer target) {
        if (!adaptiveSmoothing.getValue()) {
            return smoothing.getValue();
        }
        
        double distance = RotationUtil.distanceToEntity(target);
        double transitionDist = smoothTransitionDistance.getValue();
        
        // Close range: use closeRangeSmoothing
        if (distance <= transitionDist) {
            return closeRangeSmoothing.getValue();
        }
        
        // Far range: use farRangeSmoothing
        if (distance >= range.getValue() * 0.8) {
            return farRangeSmoothing.getValue();
        }
        
        // Transition zone: interpolate between close and far
        double ratio = (distance - transitionDist) / ((range.getValue() * 0.8) - transitionDist);
        float close = closeRangeSmoothing.getValue();
        float far = farRangeSmoothing.getValue();
        
        return close + (far - close) * (float) ratio;
    }
    
    /**
     * Apply randomization to aim (1 in 20 chance)
     */
    private void updateRandomization() {
        long currentTick = System.currentTimeMillis() / 50; // Approximate tick
        
        if (currentTick >= nextRandomizationTick) {
            // Check if randomization should trigger
            if (enableRandomization.getValue() && random.nextInt(RANDOMIZATION_CHANCE) == 0) {
                float strength = randomStrength.getValue();
                randomYawOffset = (random.nextFloat() - 0.5F) * 2.0F * strength;
                randomPitchOffset = (random.nextFloat() - 0.5F) * strength; // Less pitch variation
            } else {
                randomYawOffset = 0.0F;
                randomPitchOffset = 0.0F;
            }
            
            // Next randomization check in 10-20 ticks
            nextRandomizationTick = currentTick + 10 + random.nextInt(10);
        }
    }
    
    /**
     * Perform smooth aiming to target
     */
    private void aimAtTarget(EntityPlayer target) {
        // Predict target position
        Vec3 predictedPos = predictTargetPosition(target);
        
        // Get hitbox target position
        Vec3 targetPos = getHitboxTarget(target, predictedPos);
        
        // Get expanded bounding box for smoother targeting
        AxisAlignedBB bb = target.getEntityBoundingBox();
        double collisionBorder = target.getCollisionBorderSize();
        AxisAlignedBB expandedBB = bb.expand(collisionBorder, collisionBorder, collisionBorder);
        
        // Calculate adaptive smoothing
        float currentSmoothing = getAdaptiveSmoothing(target);
        
        // Get rotations to target
        float[] rotation = RotationUtil.getRotationsToBox(
            expandedBB,
            mc.thePlayer.rotationYaw,
            mc.thePlayer.rotationPitch,
            180.0F,
            currentSmoothing / 100.0F
        );
        
        // Calculate deltas
        float yawDelta = rotation[0] - mc.thePlayer.rotationYaw;
        float pitchDelta = rotation[1] - mc.thePlayer.rotationPitch;
        
        // Apply speed limits
        float maxYawSpeed = Math.min(Math.abs(hSpeed.getValue()), 10.0F);
        float maxPitchSpeed = Math.min(Math.abs(vSpeed.getValue()), 10.0F);
        
        // Smooth acceleration (ease in/out)
        float yawAccel = 0.1F * maxYawSpeed;
        float pitchAccel = 0.1F * maxPitchSpeed;
        
        // Apply momentum from previous tick
        lastYawDelta = lastYawDelta * 0.8F + yawDelta * yawAccel;
        lastPitchDelta = lastPitchDelta * 0.8F + pitchDelta * pitchAccel;
        
        // Update randomization
        updateRandomization();
        
        // Apply randomization offsets
        float finalYaw = mc.thePlayer.rotationYaw + lastYawDelta + randomYawOffset;
        float finalPitch = mc.thePlayer.rotationPitch + lastPitchDelta + randomPitchOffset;
        
        // Set rotation
        Myau.rotationManager.setRotation(finalYaw, finalPitch, 0, false);
    }
    
    // ==================== Event Handlers ====================
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        
        // GUI check
        if (mc.currentScreen != null) {
            return;
        }
        
        // Update rate throttling (performance)
        tickCounter++;
        if (tickCounter % updateRate.getValue() != 0) {
            return;
        }
        
        // Item check
        if (!isHoldingValidItem()) {
            lockedTarget = null;
            return;
        }
        
        // Check if attacking
        boolean isAttacking = PlayerUtil.isAttacking();
        
        // Only while attacking mode
        if (onlyWhileAttacking.getValue() && !isAttacking) {
            lockedTarget = null;
            return;
        }
        
        // Block check (don't aim at blocks)
        if (isAttacking && isLookingAtBlock()) {
            return;
        }
        
        // Timer check (grace period after attack)
        if (!isAttacking && !attackTimer.hasTimeElapsed(attackDelay.getValue())) {
            return;
        }
        
        // Get valid targets
        List<EntityPlayer> validTargets = getValidTargets();
        
        if (validTargets.isEmpty()) {
            lockedTarget = null;
            return;
        }
        
        // Prioritize in-reach targets
        List<EntityPlayer> inReachTargets = validTargets.stream()
            .filter(this::isInReach)
            .collect(Collectors.toList());
        
        if (!inReachTargets.isEmpty()) {
            validTargets = inReachTargets;
        }
        
        // Select target
        EntityPlayer target = selectTarget(validTargets);
        
        if (target == null) {
            return;
        }
        
        // Distance check
        if (RotationUtil.distanceToEntity(target) <= 0.0) {
            return;
        }
        
        // Aim at target
        aimAtTarget(target);
    }
    
    @EventTarget
    public void onKeyPress(KeyEvent event) {
        // Reset timer on attack key press
        if (event.getKey() == mc.gameSettings.keyBindAttack.getKeyCode()) {
            AutoClicker autoClicker = (AutoClicker) Myau.moduleManager.modules.get(AutoClicker.class);
            if (autoClicker == null || !autoClicker.isEnabled()) {
                attackTimer.reset();
            }
        }
    }
    
    // ==================== Module Lifecycle ====================
    
    @Override
    public void onEnable() {
        super.onEnable();
        resetState();
    }
    
    @Override
    public void onDisable() {
        super.onDisable();
        resetState();
    }
    
    private void resetState() {
        lockedTarget = null;
        lastTargetLockTime = 0L;
        tickCounter = 0;
        lastYawDelta = 0.0F;
        lastPitchDelta = 0.0F;
        randomYawOffset = 0.0F;
        randomPitchOffset = 0.0F;
        nextRandomizationTick = 0L;
    }
    
    @Override
    public String[] getSuffix() {
        if (lockedTarget != null) {
            return new String[]{lockedTarget.getName()};
        }
        return new String[]{targetMode.getValue()};
    }
                                                                    }
