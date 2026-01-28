package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.util.TimerUtil;
import myau.property.properties.FloatProperty;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.potion.Potion;
import net.minecraft.entity.EntityLivingBase;

import java.util.Random;

/**
 * Enhanced W-Tap Module
 * Provides smooth, customizable, and legitimate-looking W-tapping for PvP combat
 * 
 * Improvements:
 * - Smooth interpolation for natural movement
 * - Randomization to avoid pattern detection
 * - Health-based smart activation
 * - Multiple timing modes
 * - Better state management
 * - Performance optimized
 */
public class ImprovedWtap extends Module {
    
    // ==================== Constants ====================
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random random = new Random();
    private static final long TICK_MS = 50L; // Minecraft tick duration
    private static final long MIN_ATTACK_INTERVAL = 500L; // Minimum time between activations
    
    // ==================== Properties ====================
    // Timing Properties
    public final FloatProperty delay = new FloatProperty("Delay", 5.5F, 0.0F, 20.0F);
    public final FloatProperty duration = new FloatProperty("Duration", 1.5F, 0.5F, 10.0F);
    public final FloatProperty cooldown = new FloatProperty("Cooldown", 500F, 100F, 2000F);
    
    // Randomization Properties
    public final BooleanProperty randomize = new BooleanProperty("Randomize", true);
    public final FloatProperty randomStrength = new FloatProperty("Random Strength", 0.3F, 0.0F, 1.0F);
    
    // Smoothness Properties
    public final BooleanProperty smoothStop = new BooleanProperty("Smooth Stop", true);
    public final FloatProperty smoothFactor = new FloatProperty("Smooth Factor", 0.5F, 0.1F, 1.0F);
    
    // Smart Activation
    public final BooleanProperty smartActivation = new BooleanProperty("Smart Activation", true);
    public final FloatProperty minHealthDiff = new FloatProperty("Min Health Diff", 2.0F, 0.0F, 10.0F);
    public final BooleanProperty onlyPlayers = new BooleanProperty("Only Players", false);
    
    // Timing Modes
    public final ModeProperty timingMode = new ModeProperty("Timing Mode", "Normal", "Normal", "Fast", "Slow", "Custom");
    
    // Advanced Options
    public final BooleanProperty requireSprint = new BooleanProperty("Require Sprint", true);
    public final BooleanProperty checkVelocity = new BooleanProperty("Check Velocity", true);
    public final FloatProperty minVelocity = new FloatProperty("Min Velocity", 0.1F, 0.0F, 1.0F);
    
    // ==================== State Variables ====================
    private final TimerUtil activationTimer = new TimerUtil();
    private boolean isActive = false;
    private boolean shouldStopForward = false;
    
    // Timing variables (in milliseconds for better precision)
    private long remainingDelayMs = 0L;
    private long remainingDurationMs = 0L;
    
    // Smooth interpolation
    private float currentForwardMultiplier = 1.0F;
    private float targetForwardMultiplier = 1.0F;
    
    // Statistics (useful for debugging/tuning)
    private int activationCount = 0;
    private EntityLivingBase lastTarget = null;
    
    // ==================== Constructor ====================
    public ImprovedWtap() {
        super("WTap", false);
    }
    
    // ==================== Core Logic ====================
    
    /**
     * Checks if W-tap can be triggered based on player state
     */
    private boolean canTrigger() {
        if (mc.thePlayer == null) return false;
        
        // Basic movement check
        if (mc.thePlayer.movementInput.moveForward < 0.8F) return false;
        
        // Collision check
        if (mc.thePlayer.isCollidedHorizontally) return false;
        
        // Hunger check (if not in creative/spectator)
        if (mc.thePlayer.getFoodStats().getFoodLevel() <= 6 && !mc.thePlayer.capabilities.allowFlying) {
            return false;
        }
        
        // Sprint check (if required)
        if (requireSprint.getValue()) {
            boolean isSprinting = mc.thePlayer.isSprinting() || 
                (!mc.thePlayer.isUsingItem() && 
                 !mc.thePlayer.isPotionActive(Potion.blindness) && 
                 mc.gameSettings.keyBindSprint.isKeyDown());
            if (!isSprinting) return false;
        }
        
        // Velocity check (prevents activation when standing still)
        if (checkVelocity.getValue()) {
            double velocity = Math.sqrt(
                mc.thePlayer.motionX * mc.thePlayer.motionX + 
                mc.thePlayer.motionZ * mc.thePlayer.motionZ
            );
            if (velocity < minVelocity.getValue()) return false;
        }
        
        return true;
    }
    
    /**
     * Smart activation: only trigger if it makes tactical sense
     */
    private boolean shouldSmartActivate(EntityLivingBase target) {
        if (!smartActivation.getValue()) return true;
        if (target == null || mc.thePlayer == null) return true;
        
        // Only activate against players if configured
        if (onlyPlayers.getValue() && !(target instanceof net.minecraft.entity.player.EntityPlayer)) {
            return false;
        }
        
        // Check health difference - only W-tap if we have health advantage or are close
        float playerHealth = mc.thePlayer.getHealth();
        float targetHealth = target.getHealth();
        float healthDiff = playerHealth - targetHealth;
        
        // Activate if we have health advantage or are within threshold
        return healthDiff >= -minHealthDiff.getValue();
    }
    
    /**
     * Calculate delay with randomization
     */
    private long calculateDelay() {
        float baseDelay = delay.getValue();
        
        // Apply timing mode multipliers
        switch (timingMode.getValue()) {
            case "Fast":
                baseDelay *= 0.7F;
                break;
            case "Slow":
                baseDelay *= 1.3F;
                break;
            case "Custom":
                // Custom allows full range control
                break;
            default: // Normal
                break;
        }
        
        // Apply randomization
        if (randomize.getValue()) {
            float randomRange = baseDelay * randomStrength.getValue();
            float randomOffset = (random.nextFloat() * 2.0F - 1.0F) * randomRange;
            baseDelay += randomOffset;
        }
        
        return (long) (TICK_MS * Math.max(0, baseDelay));
    }
    
    /**
     * Calculate duration with randomization
     */
    private long calculateDuration() {
        float baseDuration = duration.getValue();
        
        // Apply timing mode multipliers
        switch (timingMode.getValue()) {
            case "Fast":
                baseDuration *= 0.8F;
                break;
            case "Slow":
                baseDuration *= 1.2F;
                break;
            case "Custom":
                // Custom allows full range control
                break;
            default: // Normal
                break;
        }
        
        // Apply randomization
        if (randomize.getValue()) {
            float randomRange = baseDuration * randomStrength.getValue() * 0.5F;
            float randomOffset = (random.nextFloat() * 2.0F - 1.0F) * randomRange;
            baseDuration += randomOffset;
        }
        
        return (long) (TICK_MS * Math.max(TICK_MS, baseDuration));
    }
    
    /**
     * Reset the W-tap state
     */
    private void resetState() {
        isActive = false;
        shouldStopForward = false;
        remainingDelayMs = 0L;
        remainingDurationMs = 0L;
        currentForwardMultiplier = 1.0F;
        targetForwardMultiplier = 1.0F;
    }
    
    /**
     * Activate W-tap
     */
    private void activate(EntityLivingBase target) {
        if (!canTrigger()) return;
        if (!shouldSmartActivate(target)) return;
        
        long currentCooldown = (long) cooldown.getValue();
        if (!activationTimer.hasTimeElapsed(currentCooldown)) return;
        
        activationTimer.reset();
        isActive = true;
        shouldStopForward = false;
        remainingDelayMs = calculateDelay();
        remainingDurationMs = calculateDuration();
        currentForwardMultiplier = 1.0F;
        targetForwardMultiplier = 1.0F;
        lastTarget = target;
        activationCount++;
    }
    
    // ==================== Event Handlers ====================
    
    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!isActive) return;
        
        // Check if we should cancel W-tap early
        if (!shouldStopForward && !canTrigger()) {
            resetState();
            return;
        }
        
        // Handle delay phase
        if (remainingDelayMs > 0L) {
            remainingDelayMs -= TICK_MS;
            return;
        }
        
        // Handle duration phase (actual W-tap)
        if (remainingDurationMs > 0L) {
            remainingDurationMs -= TICK_MS;
            shouldStopForward = true;
            
            // Smooth stopping
            if (smoothStop.getValue()) {
                // Calculate smooth transition
                float smoothSpeed = smoothFactor.getValue();
                targetForwardMultiplier = 0.0F;
                
                // Interpolate towards target
                currentForwardMultiplier += (targetForwardMultiplier - currentForwardMultiplier) * smoothSpeed;
                
                // Apply smooth multiplier
                mc.thePlayer.movementInput.moveForward *= currentForwardMultiplier;
                
                // Clamp to prevent negative movement
                if (mc.thePlayer.movementInput.moveForward < 0.0F) {
                    mc.thePlayer.movementInput.moveForward = 0.0F;
                }
            } else {
                // Hard stop
                mc.thePlayer.movementInput.moveForward = 0.0F;
            }
            
            return;
        }
        
        // W-tap complete
        if (remainingDurationMs <= 0L) {
            resetState();
        }
    }
    
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled()) return;
        if (event.isCancelled()) return;
        if (event.getType() != EventType.SEND) return;
        
        // Check if this is an attack packet
        if (!(event.getPacket() instanceof C02PacketUseEntity)) return;
        
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != Action.ATTACK) return;
        
        // Don't activate if already active
        if (isActive) return;
        
        // Don't activate if player isn't sprinting (if required)
        if (requireSprint.getValue() && !mc.thePlayer.isSprinting()) return;
        
        // Get target entity
        EntityLivingBase target = null;
        if (packet.getEntityFromWorld(mc.theWorld) instanceof EntityLivingBase) {
            target = (EntityLivingBase) packet.getEntityFromWorld(mc.theWorld);
        }
        
        // Activate W-tap
        activate(target);
    }
    
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        // Additional state validation each tick
        if (isActive && mc.thePlayer == null) {
            resetState();
        }
    }
    
    // ==================== Module Lifecycle ====================
    
    @Override
    public void onEnable() {
        super.onEnable();
        resetState();
        activationCount = 0;
    }
    
    @Override
    public void onDisable() {
        super.onDisable();
        resetState();
    }
    
    // ==================== Utility Methods ====================
    
    /**
     * Get activation statistics (useful for debugging)
     */
    public int getActivationCount() {
        return activationCount;
    }
    
    /**
     * Check if currently performing W-tap
     */
    public boolean isWTapping() {
        return isActive && shouldStopForward;
    }
    
    /**
     * Get current state for debugging
     */
    public String getStateDebug() {
        if (!isActive) return "Inactive";
        if (remainingDelayMs > 0) return "Delay: " + remainingDelayMs + "ms";
        if (remainingDurationMs > 0) return "Active: " + remainingDurationMs + "ms";
        return "Finishing";
    }
}
