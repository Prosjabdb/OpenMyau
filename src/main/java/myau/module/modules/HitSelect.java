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
 */
public class ImprovedHitSelect extends Module {
    
    // ==================== Constants ====================
    private static final Minecraft mc = Minecraft.getMinecraft();
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
    public final FloatProperty minWTapDistance = new FloatProperty("Min W-Tap Distance", 2.5F, 1.0F, 5.0F);
    
    // ==================== Advanced Properties ====================
    public final BooleanProperty allowWallHits = new BooleanProperty("Allow Wall Hits", true);
    public final FloatProperty minMovementSpeed = new FloatProperty("Min Movement Speed", 0.1F, 0.0F, 1.0F);
    public final BooleanProperty targetPlayersOnly = new BooleanProperty("Target Players Only", false);
    
    // ==================== KeepSprint Integration ====================
    public final BooleanProperty autoKeepSprint = new BooleanProperty("Auto KeepSprint", true);
    public final FloatProperty keepSprintSlowdown = new FloatProperty("KeepSprint Slowdown", 0.0F, 0.0F, 1.0F);
    public final BooleanProperty restoreKeepSprint = new BooleanProperty("Restore KeepSprint", true);
    
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
    
    // Dynamic mode state
    private String activeDynamicMode = null;
    private long lastModeSwitch = 0L;
    
    private final Map<EntityLivingBase, CombatData> combatDataMap = new HashMap<>();
    
    // ==================== Inner Classes ====================
    
    /**
     * Tracks combat data for each target
     */
    private static class CombatData {
        int hitsLanded = 0;
        int hitsBlocked = 0;
        long lastHitTime = 0L;
        int comboLength = 0;
        
        void recordHit(boolean allowed) {
            lastHitTime = System.currentTimeMillis();
            
            if (allowed) {
                hitsLanded++;
                comboLength++;
            } else {
                hitsBlocked++;
                comboLength = 0;
            }
        }
        
        boolean isInCombo() {
            return System.currentTimeMillis() - lastHitTime < 1500L && comboLength > 0;
        }
    }
    
    /**
     * Hit evaluation result
     */
    private static class HitEvaluation {
        boolean allow;
        String modeUsed;
        
        HitEvaluation(boolean allow, String mode) {
            this.allow = allow;
            this.modeUsed = mode;
        }
    }
    
    // ==================== Constructor ====================
    public ImprovedHitSelect() {
        super("HitSelect", false);
    }
    
    // ==================== Core Logic ====================
    
    private HitEvaluation evaluateHit(EntityLivingBase player, EntityLivingBase target) {
        CombatData combatData = combatDataMap.computeIfAbsent(target, k -> new CombatData());
        
        if (currentTarget != target) {
            currentTarget = target;
            lastTargetSwitchTime = System.currentTimeMillis();
            consecutiveHits = 0;
        }
        
        String activeMode = determineActiveMode(player, target, combatData);
        
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
                evaluation = new HitEvaluation(true, activeMode);
        }
        
        if (!evaluation.allow && preserveCombo.getValue() && combatData.isInCombo()) {
            if (combatData.comboLength >= minComboHits.getValue()) {
                evaluation = new HitEvaluation(true, "Combo");
            }
        }
        
        if (!evaluation.allow && healthAware.getValue()) {
            float playerHealth = player.getHealth();
            if (playerHealth <= lowHealthThreshold.getValue()) {
                evaluation = new HitEvaluation(true, "Health");
            }
        }
        
        return evaluation;
    }
    
    private String determineActiveMode(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        String mode = primaryMode.getValue();
        
        if (!mode.equals("Smart") || !dynamicSwitching.getValue()) {
            return mode;
        }
        
        long timeSinceLastSwitch = System.currentTimeMillis() - lastModeSwitch;
        if (timeSinceLastSwitch < 500L) {
            return activeDynamicMode != null ? activeDynamicMode : "Second";
        }
        
        float playerHealth = player.getHealth();
        float targetHealth = target.getHealth();
        double distance = player.getDistanceToEntity(target);
        boolean isInCombo = data.isInCombo();
        
        String selectedMode;
        
        if (isInCombo && data.comboLength >= 2) {
            selectedMode = "Combo";
        } else if (playerHealth <= lowHealthThreshold.getValue()) {
            selectedMode = "Health";
        } else if (targetHealth <= lowHealthThreshold.getValue()) {
            selectedMode = "Criticals";
        } else if (player.onGround && player.fallDistance == 0 && !target.onGround) {
            selectedMode = "Criticals";
        } else if (distance > optimalReach.getValue() - reachTolerance.getValue()) {
            selectedMode = "Reach";
        } else if (!sprintState && mc.gameSettings.keyBindForward.isKeyDown()) {
            selectedMode = "W-Tap";
        } else {
            selectedMode = "Second";
        }
        
        if (!selectedMode.equals(activeDynamicMode)) {
            activeDynamicMode = selectedMode;
            lastModeSwitch = System.currentTimeMillis();
        }
        
        return selectedMode;
    }
    
    private HitEvaluation evaluateSmartMode(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        int score = 0;
        
        if (target.hurtTime != 0) score += 40;
        if (player.onGround || player.fallDistance > minFallDistance.getValue()) score += 30;
        
        double distance = player.getDistanceToEntity(target);
        if (distance >= 2.5 && distance <= optimalReach.getValue()) score += 20;
        if (sprintState) score += 10;
        
        boolean allow = score >= (int)(50 * adaptSensitivity.getValue());
        return new HitEvaluation(allow, "Smart");
    }
    
    private HitEvaluation evaluateSecondHit(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        if (target.hurtTime != 0) {
            return new HitEvaluation(true, "Second");
        }
        
        if (player.hurtTime > 0 && player.hurtTime <= player.maxHurtTime - 1) {
            return new HitEvaluation(true, "Second");
        }
        
        double distance = player.getDistanceToEntity(target);
        if (distance < 2.5) {
            return new HitEvaluation(true, "Second");
        }
        
        boolean targetMovingTowards = isMovingTowards(target, player, 60.0);
        boolean playerMovingTowards = isMovingTowards(player, target, 60.0);
        
        if (!targetMovingTowards || !playerMovingTowards) {
            return new HitEvaluation(true, "Second");
        }
        
        return new HitEvaluation(false, "Second");
    }
    
    private HitEvaluation evaluateCriticalHits(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        if (player.onGround) {
            return new HitEvaluation(true, "Criticals");
        }
        
        if (player.hurtTime != 0) {
            return new HitEvaluation(true, "Criticals");
        }
        
        boolean isFalling = player.fallDistance > minFallDistance.getValue();
        boolean hasDownwardMotion = player.motionY < -0.1;
        
        if (isFalling || (hasDownwardMotion && allowJumpCrits.getValue())) {
            return new HitEvaluation(true, "Criticals");
        }
        
        return new HitEvaluation(false, "Criticals");
    }
    
    private HitEvaluation evaluateWTapHits(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        if (allowWallHits.getValue() && player.isCollidedHorizontally) {
            return new HitEvaluation(true, "W-Tap");
        }
        
        if (!mc.gameSettings.keyBindForward.isKeyDown()) {
            return new HitEvaluation(true, "W-Tap");
        }
        
        if (sprintState) {
            return new HitEvaluation(true, "W-Tap");
        }
        
        if (strictWTap.getValue()) {
            double distance = player.getDistanceToEntity(target);
            if (distance < minWTapDistance.getValue()) {
                return new HitEvaluation(true, "W-Tap");
            }
        }
        
        return new HitEvaluation(false, "W-Tap");
    }
    
    private HitEvaluation evaluateComboMode(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        if (data.isInCombo()) {
            return new HitEvaluation(true, "Combo");
        }
        
        if (target.hurtTime != 0) {
            return new HitEvaluation(true, "Combo");
        }
        
        double distance = player.getDistanceToEntity(target);
        if (distance < 3.0 && sprintState) {
            return new HitEvaluation(true, "Combo");
        }
        
        return new HitEvaluation(false, "Combo");
    }
    
    private HitEvaluation evaluateReachMode(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        double distance = player.getDistanceToEntity(target);
        double minReach = optimalReach.getValue() - reachTolerance.getValue();
        double maxReach = optimalReach.getValue() + reachTolerance.getValue();
        
        if (distance >= minReach && distance <= maxReach) {
            return new HitEvaluation(true, "Reach");
        }
        
        if (target.hurtTime != 0) {
            return new HitEvaluation(true, "Reach");
        }
        
        return new HitEvaluation(false, "Reach");
    }
    
    private HitEvaluation evaluateHealthMode(EntityLivingBase player, EntityLivingBase target, CombatData data) {
        float playerHealth = player.getHealth();
        float targetHealth = target.getHealth();
        float healthDiff = playerHealth - targetHealth;
        
        if (healthDiff >= healthAdvantage.getValue()) {
            return new HitEvaluation(true, "Health");
        }
        
        if (playerHealth <= lowHealthThreshold.getValue()) {
            return new HitEvaluation(true, "Health");
        }
        
        if (targetHealth <= lowHealthThreshold.getValue()) {
            return new HitEvaluation(true, "Health");
        }
        
        if (healthDiff < -healthAdvantage.getValue()) {
            if (player.fallDistance > 0 || target.hurtTime != 0) {
                return new HitEvaluation(true, "Health");
            }
            return new HitEvaluation(false, "Health");
        }
        
        return new HitEvaluation(true, "Health");
    }
    
    private void applyKeepSprintFix() {
        if (!autoKeepSprint.getValue() || keepSprintModified) {
            return;
        }
        
        try {
            KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
            if (keepSprint == null) {
                return;
            }
            
            savedKeepSprintState = keepSprint.isEnabled();
            savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
            
            if (!keepSprint.isEnabled()) {
                keepSprint.toggle();
            }
            keepSprint.slowdown.setValue((int) (keepSprintSlowdown.getValue() * 100));
            
            keepSprintModified = true;
        } catch (Exception e) {
            // Silent fail
        }
    }
    
    private void restoreKeepSprintState() {
        if (!keepSprintModified || !restoreKeepSprint.getValue()) {
            return;
        }
        
        try {
            KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
            if (keepSprint == null) {
                keepSprintModified = false;
                return;
            }
            
            keepSprint.slowdown.setValue((int) savedSlowdown);
            
            if (keepSprint.isEnabled() != savedKeepSprintState) {
                keepSprint.toggle();
            }
            
            keepSprintModified = false;
        } catch (Exception e) {
            // Silent fail
        }
    }
    
    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngleDegrees) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();
        
        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);
        
        if (movementLength < minMovementSpeed.getValue() * 0.05) {
            return false;
        }
        
        mx /= movementLength;
        mz /= movementLength;
        
        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);
        
        if (targetLength == 0.0) {
            return false;
        }
        
        tx /= targetLength;
        tz /= targetLength;
        
        double dotProduct = mx * tx + mz * tz;
        
        return dotProduct >= Math.cos(Math.toRadians(maxAngleDegrees));
    }
    
    private void cleanupCombatData() {
        long now = System.currentTimeMillis();
        combatDataMap.entrySet().removeIf(entry -> 
            now - entry.getValue().lastHitTime > COMBAT_TIMEOUT_MS
        );
    }
    
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled()) {
            return;
        }
        
        if (event.getType() == EventType.POST) {
            restoreKeepSprintState();
            
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
            C02PacketUseEntity usePacket = (C02PacketUseEntity) event.getPacket();
            
            if (usePacket.getAction() != C02PacketUseEntity.Action.ATTACK) {
                return;
            }
            
            Entity targetEntity = usePacket.getEntityFromWorld(mc.theWorld);
            
            if (targetEntity == null || targetEntity instanceof EntityLargeFireball) {
                return;
            }
            
            if (!(targetEntity instanceof EntityLivingBase)) {
                return;
            }
            
            EntityLivingBase target = (EntityLivingBase) targetEntity;
            
            if (targetPlayersOnly.getValue() && !(target instanceof EntityPlayer)) {
                return;
            }
            
            HitEvaluation evaluation = evaluateHit(mc.thePlayer, target);
            
            CombatData combatData = combatDataMap.get(target);
            if (combatData != null) {
                combatData.recordHit(evaluation.allow);
            }
            
            if (evaluation.allow) {
                consecutiveHits++;
                lastHitTime = System.currentTimeMillis();
            } else {
                consecutiveHits = 0;
                applyKeepSprintFix();
            }
            
            if (!evaluation.allow) {
                event.setCancelled(true);
            }
        }
    }
    
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
    }
    
    @Override
    public String[] getSuffix() {
        return new String[]{
            activeDynamicMode != null ? activeDynamicMode : primaryMode.getValue()
        };
    }
}
