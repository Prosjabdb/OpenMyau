package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntegerProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Enhanced HitSelect Module
 * Intelligently filters attacks to optimize PvP performance
 * 
 * Features:
 * - Multiple intelligent selection strategies
 * - Dynamic mode switching based on combat state
 * - Combo preservation logic
 * - Health-based decision making
 * - Clean KeepSprint integration
 * - Advanced movement analysis
 * - Performance statistics tracking
 */
public class ImprovedHitSelect extends Module {
    
    // ==================== Constants ====================
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MAX_COMBO_HISTORY = 10;
    private static final long COMBAT_TIMEOUT_MS = 3000L;
    
    // ==================== Mode Properties ====================
    public final ModeProperty primaryMode = new ModeProperty(
        "Primary Mode", 
        "Smart",
        "Smart", "Second", "Criticals", "W-Tap", "Combo", "Reach", "Health"
    );
    
    public final ModeProperty fallbackMode = new ModeProperty(
        "Fallback Mode",
        "Second",
        "None", "Second", "Criticals", "W-Tap"
    );
    
    // ==================== Smart Mode Properties ====================
    public final BooleanProperty dynamicSwitching = new BooleanProperty("Dynamic Switching", true);
    public final BooleanProperty adaptToTarget = new BooleanProperty("Adapt To Target", true);
    public final FloatProperty adaptSensitivity = new FloatProperty("Adapt Sensitivity", 0.7F, 0.1F, 1.0F);
    
    // ==================== Combo Properties ====================
    public final BooleanProperty preserveCombo = new BooleanProperty("Preserve Combo", true);
    public final IntegerProperty minComboHits = new IntegerProperty("Min Combo Hits", 3, 1, 10);
    public final FloatProperty comboTimeout = new FloatProperty("Combo Timeout", 1.5F, 0.5F, 3.0F);
    
    // ==================== Health-Based Properties ====================
    public final BooleanProperty healthAware = new BooleanProperty("Health Aware", true);
    public final FloatProperty lowHealthThreshold = new FloatProperty("Low Health Threshold", 6.0F, 1.0F, 20.0F);
    public final FloatProperty healthAdvantage = new FloatProperty("Health Advantage", 4.0F, 0.0F, 10.0F);
    
    // ==================== Critical Properties ====================
    public final FloatProperty minFallDistance = new FloatProperty("Min Fall Distance", 0.0F, 0.0F, 2.0F);
    public final BooleanProperty allowJumpCrits = new BooleanProperty("Allow Jump Crits", true);
    
    // ==================== Reach Properties ====================
    public final FloatProperty optimalReach = new FloatProperty("Optimal Reach", 3.2F, 2.5F, 4.0F);
    public final FloatProperty reachTolerance = new FloatProperty("Reach Tolerance", 0.5F, 0.1F, 1.0F);
    
    // ==================== W-Tap Properties ====================
    public final BooleanProperty strictWTap = new BooleanProperty("Strict W-Tap", false);
    public final FloatProperty minWTapDistance = new FloatProperty("Min W-Tap Distance", 2.5, 1.0F, 5.0F);
    
    // ==================== Advanced Properties ====================
    public final BooleanProperty allowWallHits = new BooleanProperty("Allow Wall Hits", true);
    public final FloatProperty minMovementSpeed = new FloatProperty("Min Movement Speed", 0.1F, 0.0F, 1.0F);
    public final BooleanProperty targetPlayersOnly = new BooleanProperty("Target Players Only", false);
    
    // ==================== KeepSprint Integration ====================
    public final BooleanProperty autoKeepSprint = new BooleanProperty("Auto KeepSprint", true);
    public final FloatProperty keepSprintSlowdown = new FloatProperty("KeepSprint Slowdown", 0.0F, 0.0F, 1.0F);
    public final BooleanProperty restoreKeepSprint = new BooleanProperty("Restore KeepSprint", true);
    
    // ==================== Debug & Statistics ====================
    public final BooleanProperty showStats = new BooleanProperty("Show Stats", false);
    
    // ==================== State Variables ====================
    private boolean sprintState = false;
    private boolean keepSprintModified = false;
    private double savedSlowdown = 0.0;
    private boolean savedKeepSprintState = false;
    
    // Combat state tracking
    private EntityLivingBase currentTarget = null;
    private long lastHitTime = 0L;
    private int consecutiveHits = 0;
    private long lastTargetSwitchTime = 0L;
    
    // Statistics
    private int totalHitsBlocked = 0;
    private int totalHitsAllowed = 0;
    private final Map<String, Integer> modeUsageCount = new HashMap<>();
    private final Map<EntityLivingBase, CombatData> combatDataMap = new HashMap<>();
    
    // Dynamic mode state
    private String activeDynamicMode = null;
    private long lastModeSwitch = 0L;
    
    // ==================== Inner Classes ====================
    
    /**
     * Tracks combat data for each target
     */
    private static class CombatData {
        int hitsLanded = 0;
        int hitsBlocked = 0;
        long firstHitTime = 0L;
        long lastHitTime = 0L;
        double averageDistance = 0.0;
        int criticalHits = 0;
        int comboLength = 0;
        
        void recordHit(boolean allowed, double distance, boolean critical) {
            long now = System.currentTimeMillis();
            if (firstHitTime == 0L) firstHitTime = now;
            lastHitTime = now;
            
            if (allowed) {
                hitsLanded++;
                if (critical) criticalHits++;
                comboLength++;
                // Update rolling average distance
                averageDistance = (averageDistance * (hitsLanded - 1) + distance) / hitsLanded;
            } else {
                hitsBlocked++;
                comboLength = 0; // Reset combo on blocked hit
            }
        }
        
        boolean isInCombo() {
            return System.currentTimeMillis() - lastHitTime < 1500L && comboLength > 0;
        }
        
        double getSuccessRate() {
            int total = hitsLanded + hitsBlocked;
            return total == 0 ? 0.0 : (double) hitsLanded / total;
        }
    }
    
    /**
     * Hit evaluation result
     */
    private static class HitEvaluation {
        boolean allow;
        String reason;
        String modeUsed;
        int confidence; // 0-100
        
        HitEvaluation(boolean allow, String reason, String mode, int confidence) {
            this.allow = allow;
            this.reason = reason;
            this.modeUsed = mode;
            this.confidence = confidence;
        }
    }
    
    // ==================== Constructor ====================
    public ImprovedHitSelect() {
        super("HitSelect", false);
    }
    
    // ==================== Core Logic ====================
    
    /**
     * Main hit evaluation logic with smart decision making
     */
    private HitEvaluation evaluateHit(EntityLivingBase player, EntityLivingBase target) {
        // Get or create combat data
        CombatData combatData = combatDataMap.computeIfAbsent(target, k -> new CombatData());
        
        // Update target tracking
        if (currentTarget != target) {
            currentTarget = target;
            lastTargetSwitchTime = System.currentTimeMillis();
            consecutiveHits = 0;
        }
        
        // Determine active mode
        String activeMode = determineActiveMode(player, target, combatData);
        
        // Evaluate based on mode
        HitEvaluation evaluation;
        switch (activeMode) {
            case "Smart":
                evaluation = evaluateSmartMode(player, target, combatData);
                break;
            case "Second":
                evaluation = evaluateSecondHit(player, target, combatData);
                break;
            case "Criticals":
                evaluation = evaluateCriticalHits(player, target, combatData);
                break;
            case "W-Tap":
                evaluation = evaluateWTapHits(player, target, combatData);
                break;
            case "Combo":
                evaluation = evaluateComboMode(player, target, combatData);
                break;
            case "Reach":
                evaluation = evaluateReachMode(player, target, combatData);
                break;
            case "Health":
                evaluation = evaluateHealthMode(player, target, combatData);
                break;
            default:
                evaluation = new HitEvaluation(true, "Unknown mode", activeMode, 50);
        }
        
        // Apply combo preservation override
        if (!evaluation.allow && preserveCombo.getValue() && combatData.isInCombo()) {
            if (combatData.comboLength >= minComboHits.getValue()) {
                evaluation = new HitEvaluation(true, "Combo preservation override", "Combo", 90);
            }
        }
        
        // Apply health-aware override (emergency situations)
        if (!evaluation.allow && healthAware.getValue()) {
            float playerHealth = player.getHealth();
            if (playerHealth <= lowHealthThreshold.getValue()) {
                evaluation = new HitEvaluation(true, "Low health emergency override", "Health", 95);
            }
        }
        
        return evaluation;
    }
    
    /**
     * Determine which mode should be active (dynamic switching)
     */
    private String determineActiveMode(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        String mode = primaryMode.getValue();
        
        // If not smart mode or dynamic switching disabled, return primary mode
        if (!mode.equals("Smart") || !dynamicSwitching.getValue()) {
            return mode;
        }
        
        // Smart mode: analyze combat state and switch dynamically
        long timeSinceLastSwitch = System.currentTimeMillis() - lastModeSwitch;
        if (timeSinceLastSwitch < 500L) {
            // Don't switch too frequently
            return activeDynamicMode != null ? activeDynamicMode : "Second";
        }
        
        // Analyze combat situation
        float playerHealth = player.getHealth();
        float targetHealth = target.getHealth();
        double distance = player.getDistanceToEntity(target);
        boolean isInCombo = data.isInCombo();
        double successRate = data.getSuccessRate();
        
        // Decision tree for dynamic mode selection
        String selectedMode;
        
        // Priority 1: Combo preservation
        if (isInCombo && data.comboLength >= 2) {
            selectedMode = "Combo";
        }
        // Priority 2: Health-based decisions
        else if (playerHealth <= lowHealthThreshold.getValue()) {
            selectedMode = "Health"; // Aggressive when low
        }
        else if (targetHealth <= lowHealthThreshold.getValue()) {
            selectedMode = "Criticals"; // Finish them off
        }
        // Priority 3: Positional advantage
        else if (player.onGround && player.fallDistance == 0 && !target.onGround) {
            selectedMode = "Criticals"; // Wait for crit opportunity
        }
        else if (distance > optimalReach.getValue() - reachTolerance.getValue()) {
            selectedMode = "Reach"; // Optimize reach
        }
        // Priority 4: Sprint state
        else if (!sprintState && mc.gameSettings.keyBindForward.isKeyDown()) {
            selectedMode = "W-Tap"; // Build sprint
        }
        // Default: Second hit strategy (reliable)
        else {
            selectedMode = "Second";
        }
        
        // Update active mode if changed
        if (!selectedMode.equals(activeDynamicMode)) {
            activeDynamicMode = selectedMode;
            lastModeSwitch = System.currentTimeMillis();
        }
        
        return selectedMode;
    }
    
    /**
     * Smart mode: Uses AI-like decision making
     */
    private HitEvaluation evaluateSmartMode(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        // Combine multiple factors for intelligent decision
        int score = 0;
        StringBuilder reasoning = new StringBuilder("Smart: ");
        
        // Factor 1: Target hurt time (40 points)
        if (target.hurtTime != 0) {
            score += 40;
            reasoning.append("target-hurt ");
        }
        
        // Factor 2: Player state (30 points)
        if (player.onGround || player.fallDistance > minFallDistance.getValue()) {
            score += 30;
            reasoning.append("good-position ");
        }
        
        // Factor 3: Distance (20 points)
        double distance = player.getDistanceToEntity(target);
        if (distance >= 2.5 && distance <= optimalReach.getValue()) {
            score += 20;
            reasoning.append("optimal-reach ");
        }
        
        // Factor 4: Sprint state (10 points)
        if (sprintState) {
            score += 10;
            reasoning.append("sprinting ");
        }
        
        // Threshold: 50+ points = allow hit
        boolean allow = score >= (int)(50 * adaptSensitivity.getValue());
        
        return new HitEvaluation(allow, reasoning.toString(), "Smart", score);
    }
    
    /**
     * Second hit strategy: Prioritize hits when target is vulnerable
     */
    private HitEvaluation evaluateSecondHit(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        // Allow if target is already hurt
        if (target.hurtTime != 0) {
            return new HitEvaluation(true, "Target in hurt time", "Second", 95);
        }
        
        // Allow if player is recovering
        if (player.hurtTime > 0 && player.hurtTime <= player.maxHurtTime - 1) {
            return new HitEvaluation(true, "Player recovering", "Second", 85);
        }
        
        double distance = player.getDistanceToEntity(target);
        
        // Allow if too close (can't avoid)
        if (distance < 2.5) {
            return new HitEvaluation(true, "Too close to avoid", "Second", 80);
        }
        
        // Check movement towards each other
        boolean targetMovingTowards = isMovingTowards(target, player, 60.0);
        boolean playerMovingTowards = isMovingTowards(player, target, 60.0);
        
        // Allow if not approaching
        if (!targetMovingTowards) {
            return new HitEvaluation(true, "Target not approaching", "Second", 75);
        }
        
        if (!playerMovingTowards) {
            return new HitEvaluation(true, "Player not approaching", "Second", 75);
        }
        
        // Block the hit - they're approaching and will trade
        return new HitEvaluation(false, "Approaching trade - block first hit", "Second", 70);
    }
    
    /**
     * Critical hits strategy: Only hit when can deal critical damage
     */
    private HitEvaluation evaluateCriticalHits(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        // Always allow if on ground (normal hit)
        if (player.onGround) {
            return new HitEvaluation(true, "On ground - normal hit", "Criticals", 60);
        }
        
        // Allow if hurt (recovery priority)
        if (player.hurtTime != 0) {
            return new HitEvaluation(true, "In hurt time - allow", "Criticals", 55);
        }
        
        // Check for critical conditions
        boolean isFalling = player.fallDistance > minFallDistance.getValue();
        boolean hasDownwardMotion = player.motionY < -0.1;
        
        // Allow if can crit
        if (isFalling || (hasDownwardMotion && allowJumpCrits.getValue())) {
            return new HitEvaluation(true, "Critical hit opportunity", "Criticals", 95);
        }
        
        // Block if jumping up (no crit possible)
        return new HitEvaluation(false, "Cannot crit - wait for fall", "Criticals", 80);
    }
    
    /**
     * W-Tap strategy: Only hit when sprinting
     */
    private HitEvaluation evaluateWTapHits(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        // Allow if against wall
        if (allowWallHits.getValue() && player.isCollidedHorizontally) {
            return new HitEvaluation(true, "Against wall", "W-Tap", 70);
        }
        
        // Allow if not moving forward
        if (!mc.gameSettings.keyBindForward.isKeyDown()) {
            return new HitEvaluation(true, "Not moving forward", "W-Tap", 65);
        }
        
        // Allow if sprinting
        if (sprintState) {
            return new HitEvaluation(true, "Currently sprinting", "W-Tap", 90);
        }
        
        // Check distance for strict mode
        if (strictWTap.getValue()) {
            double distance = player.getDistanceToEntity(target);
            if (distance < minWTapDistance.getValue()) {
                return new HitEvaluation(true, "Too close for strict W-tap", "W-Tap", 75);
            }
        }
        
        // Block - need to build sprint
        return new HitEvaluation(false, "Building sprint", "W-Tap", 85);
    }
    
    /**
     * Combo mode: Maintain hit combo
     */
    private HitEvaluation evaluateComboMode(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        // Always allow if in combo
        if (data.isInCombo()) {
            return new HitEvaluation(true, "Maintaining combo (" + data.comboLength + ")", "Combo", 95);
        }
        
        // Start combo if target is vulnerable
        if (target.hurtTime != 0) {
            return new HitEvaluation(true, "Starting combo", "Combo", 90);
        }
        
        // Check if we have advantage
        double distance = player.getDistanceToEntity(target);
        if (distance < 3.0 && sprintState) {
            return new HitEvaluation(true, "Combo initiation", "Combo", 85);
        }
        
        // Wait for better opportunity
        return new HitEvaluation(false, "Waiting for combo opportunity", "Combo", 70);
    }
    
    /**
     * Reach mode: Optimize hit distance
     */
    private HitEvaluation evaluateReachMode(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        double distance = player.getDistanceToEntity(target);
        double minReach = optimalReach.getValue() - reachTolerance.getValue();
        double maxReach = optimalReach.getValue() + reachTolerance.getValue();
        
        // Allow if in optimal range
        if (distance >= minReach && distance <= maxReach) {
            return new HitEvaluation(true, String.format("Optimal reach (%.2f)", distance), "Reach", 95);
        }
        
        // Allow if target is hurt (don't miss opportunity)
        if (target.hurtTime != 0) {
            return new HitEvaluation(true, "Target hurt - take opportunity", "Reach", 85);
        }
        
        // Too close
        if (distance < minReach) {
            return new HitEvaluation(false, String.format("Too close (%.2f < %.2f)", distance, minReach), "Reach", 75);
        }
        
        // Too far (shouldn't happen, but handle it)
        return new HitEvaluation(false, String.format("Too far (%.2f > %.2f)", distance, maxReach), "Reach", 70);
    }
    
    /**
     * Health mode: Make decisions based on health
     */
    private HitEvaluation evaluateHealthMode(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        float playerHealth = player.getHealth();
        float targetHealth = target.getHealth();
        float healthDiff = playerHealth - targetHealth;
        
        // If we have health advantage, be aggressive
        if (healthDiff >= healthAdvantage.getValue()) {
            return new HitEvaluation(true, String.format("Health advantage (+%.1f)", healthDiff), "Health", 90);
        }
        
        // If we're low health, be aggressive (nothing to lose)
        if (playerHealth <= lowHealthThreshold.getValue()) {
            return new HitEvaluation(true, "Low health - aggressive", "Health", 95);
        }
        
        // If target is low, finish them
        if (targetHealth <= lowHealthThreshold.getValue()) {
            return new HitEvaluation(true, "Target low - finish", "Health", 95);
        }
        
        // If we're at disadvantage, be cautious
        if (healthDiff < -healthAdvantage.getValue()) {
            // Only hit if we can crit or target is hurt
            if (player.fallDistance > 0 || target.hurtTime != 0) {
                return new HitEvaluation(true, "Disadvantage but good opportunity", "Health", 75);
            }
            return new HitEvaluation(false, String.format("Health disadvantage (%.1f)", healthDiff), "Health", 80);
        }
        
        // Neutral health - normal strategy
        return new HitEvaluation(true, "Neutral health", "Health", 70);
    }
    
    // ==================== KeepSprint Integration ====================
    
    /**
     * Apply KeepSprint modifications when blocking hits
     */
    private void applyKeepSprintFix() {
        if (!autoKeepSprint.getValue() || keepSprintModified) {
            return;
        }
        
        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }
        
        try {
            // Save original state
            savedKeepSprintState = keepSprint.isEnabled();
            savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
            
            // Enable and configure KeepSprint
            if (!keepSprint.isEnabled()) {
                keepSprint.toggle();
            }
            keepSprint.slowdown.setValue((int) (keepSprintSlowdown.getValue() * 100));
            
            keepSprintModified = true;
        } catch (Exception e) {
            // Silent fail - KeepSprint integration is optional
        }
    }
    
    /**
     * Restore KeepSprint to original state
     */
    private void restoreKeepSprintState() {
        if (!keepSprintModified || !restoreKeepSprint.getValue()) {
            return;
        }
        
        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            keepSprintModified = false;
            return;
        }
        
        try {
            // Restore original settings
            keepSprint.slowdown.setValue((int) savedSlowdown);
            
            if (keepSprint.isEnabled() != savedKeepSprintState) {
                keepSprint.toggle();
            }
            
            keepSprintModified = false;
        } catch (Exception e) {
            // Silent fail
        }
    }
    
    // ==================== Utility Methods ====================
    
    /**
     * Check if entity is moving towards target
     */
    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngleDegrees) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();
        
        // Calculate movement vector
        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);
        
        // If not moving, return false
        if (movementLength < minMovementSpeed.getValue() * 0.05) {
            return false;
        }
        
        // Normalize movement vector
        mx /= movementLength;
        mz /= movementLength;
        
        // Calculate vector to target
        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);
        
        if (targetLength == 0.0) {
            return false;
        }
        
        // Normalize target vector
        tx /= targetLength;
        tz /= targetLength;
        
        // Calculate dot product
        double dotProduct = mx * tx + mz * tz;
        
        // Check if angle is within threshold
        return dotProduct >= Math.cos(Math.toRadians(maxAngleDegrees));
    }
    
    /**
     * Clean up old combat data
     */
    private void cleanupCombatData() {
        long now = System.currentTimeMillis();
        combatDataMap.entrySet().removeIf(entry -> 
            now - entry.getValue().lastHitTime > COMBAT_TIMEOUT_MS
        );
    }
    
    /**
     * Update mode usage statistics
     */
    private void updateModeStats(String mode) {
        modeUsageCount.put(mode, modeUsageCount.getOrDefault(mode, 0) + 1);
    }
    
    // ==================== Event Handlers ====================
    
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled()) {
            return;
        }
        
        if (event.getType() == EventType.POST) {
            // Restore KeepSprint state after processing
            restoreKeepSprintState();
            
            // Cleanup old combat data periodically
            if (System.currentTimeMillis() % 100 == 0) {
                cleanupCombatData();
            }
        }
    }
    
    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }
        
        // Track sprint state
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
        
        // Process attack packets
        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity usePacket = (C02PacketUseEntity) event.getPacket();
            
            if (usePacket.getAction() != C02PacketUseEntity.Action.ATTACK) {
                return;
            }
            
            Entity targetEntity = usePacket.getEntityFromWorld(mc.theWorld);
            
            // Ignore invalid targets
            if (targetEntity == null || targetEntity instanceof EntityLargeFireball) {
                return;
            }
            
            if (!(targetEntity instanceof EntityLivingBase)) {
                return;
            }
            
            EntityLivingBase target = (EntityLivingBase) targetEntity;
            
            // Player-only filter
            if (targetPlayersOnly.getValue() && !(target instanceof EntityPlayer)) {
                return;
            }
            
            // Evaluate hit
            HitEvaluation evaluation = evaluateHit(mc.thePlayer, target);
            
            // Record combat data
            CombatData combatData = combatDataMap.get(target);
            if (combatData != null) {
                double distance = mc.thePlayer.getDistanceToEntity(target);
                boolean isCrit = !mc.thePlayer.onGround && mc.thePlayer.fallDistance > 0;
                combatData.recordHit(evaluation.allow, distance, isCrit);
            }
            
            // Update statistics
            if (evaluation.allow) {
                totalHitsAllowed++;
                consecutiveHits++;
                lastHitTime = System.currentTimeMillis();
            } else {
                totalHitsBlocked++;
                consecutiveHits = 0;
                
                // Apply KeepSprint fix
                applyKeepSprintFix();
            }
            
            updateModeStats(evaluation.modeUsed);
            
            // Cancel if blocked
            if (!evaluation.allow) {
                event.setCancelled(true);
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
        restoreKeepSprintState();
        resetState();
    }
    
    private void resetState() {
        sprintState = false;
        keepSprintModified = false;
        savedSlowdown = 0.0;
        savedKeepSprintState = false;
        currentTarget = null;
        lastHitTime = 0L;
        consecutiveHits = 0;
        lastTargetSwitchTime = 0L;
        activeDynamicMode = null;
        lastModeSwitch = 0L;
        combatDataMap.clear();
        
        if (!showStats.getValue()) {
            totalHitsBlocked = 0;
            totalHitsAllowed = 0;
            modeUsageCount.clear();
        }
    }
    
    @Override
    public String[] getSuffix() {
        if (showStats.getValue()) {
            int total = totalHitsAllowed + totalHitsBlocked;
            double successRate = total == 0 ? 0 : (totalHitsAllowed * 100.0 / total);
            return new String[]{
                String.format("%s (%.0f%%)", 
                    activeDynamicMode != null ? activeDynamicMode : primaryMode.getValue(),
                    successRate
                )
            };
        }
        return new String[]{
            activeDynamicMode != null ? activeDynamicMode : primaryMode.getValue()
        };
    }
    
    // ==================== Public API ====================
    
    /**
     * Get current combat statistics
     */
    public String getStatistics() {
        int total = totalHitsAllowed + totalHitsBlocked;
        if (total == 0) return "No hits recorded";
        
        double successRate = (totalHitsAllowed * 100.0 / total);
        double blockRate = (totalHitsBlocked * 100.0 / total);
        
        StringBuilder stats = new StringBuilder();
        stats.append(String.format("Total: %d | Allowed: %d (%.1f%%) | Blocked: %d (%.1f%%)\n",
            total, totalHitsAllowed, successRate, totalHitsBlocked, blockRate));
        
        if (!modeUsageCount.isEmpty()) {
            stats.append("Mode Usage: ");
            modeUsageCount.forEach((mode, count) -> 
                stats.append(String.format("%s:%d ", mode, count))
            );
        }
        
        return stats.toString();
    }
    
    /**
     * Get combat data for specific target
     */
    public CombatData getCombatData(EntityLivingBase target) {
        return combatDataMap.get(target);
    }
    
    /**
     * Check if currently in combat
     */
    public boolean isInCombat() {
        return System.currentTimeMillis() - lastHitTime < COMBAT_TIMEOUT_MS;
    }
    
    /**
     * Get current combo length
     */
    public int getCurrentCombo() {
        return consecutiveHits;
    }
                    }
