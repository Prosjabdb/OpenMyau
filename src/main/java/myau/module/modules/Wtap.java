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
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;

import java.util.HashMap;
import java.util.Map;

public class Wtap extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Timers
    private final TimerUtil attackTimer = new TimerUtil();
    private final TimerUtil hitTimer = new TimerUtil();
    private final TimerUtil adaptiveAnalysisTimer = new TimerUtil();
    
    // State tracking
    private boolean active = false;
    private boolean stopForward = false;
    private int delayTicks = 0;
    private int durationTicks = 0;
    private int hitCounter = 0;
    private boolean waitingForFall = false;
    private EntityLivingBase lastTarget = null;
    
    // Adaptive mode intelligence
    private String currentAdaptiveMode = "Offensive";
    private final Map<Integer, CombatData> targetDataMap = new HashMap<>();
    private long lastHitTime = 0L;
    private long lastHitReceivedTime = 0L;
    private int consecutiveHitsDealt = 0;
    private int consecutiveHitsReceived = 0;
    private double lastDistance = 0.0;
    private boolean isBeingComboed = false;
    private int ticksSinceLastHit = 0;
    
    // Properties
    public final ModeProperty mode = new ModeProperty("Mode", "Adaptive", 
        "Adaptive", "Offensive", "Defensive", "Light", "Zest", "Smart");
    public final FloatProperty delay = new FloatProperty("Delay", 0.0F, 0.0F, 10.0F);
    public final FloatProperty duration = new FloatProperty("Duration", 1.0F, 0.5F, 5.0F);
    public final FloatProperty chance = new FloatProperty("Chance", 100.0F, 0.0F, 100.0F);
    public final BooleanProperty onlyPlayers = new BooleanProperty("Only Players", true);
    public final BooleanProperty onlySprinting = new BooleanProperty("Only Sprinting", true);
    public final BooleanProperty requireHunger = new BooleanProperty("Require Hunger", true);
    
    // Advanced settings
    public final FloatProperty cooldown = new FloatProperty("Cooldown", 500.0F, 0.0F, 2000.0F);
    public final BooleanProperty smartReset = new BooleanProperty("Smart Reset", true);
    
    // Adaptive mode settings
    public final FloatProperty adaptiveSensitivity = new FloatProperty("Adaptive Sensitivity", 3.0F, 1.0F, 10.0F);
    public final BooleanProperty adaptiveDebug = new BooleanProperty("Adaptive Debug", false);
    public final BooleanProperty preferLight = new BooleanProperty("Prefer Light", true);
    public final BooleanProperty autoDefensive = new BooleanProperty("Auto Defensive", true);
    public final BooleanProperty detectHypixel = new BooleanProperty("Detect Hypixel", true);

    public Wtap() {
        super("WTap", false);
    }

    /**
     * Combat data tracker for each opponent
     */
    private static class CombatData {
        int hitsDealt = 0;
        int hitsReceived = 0;
        long lastHitTime = 0L;
        double averageDistance = 0.0;
        int totalEngagements = 0;
        boolean likelyHypixel = false;
        int consecutiveComboHits = 0;
        long combatStartTime = 0L;
        double knockbackStrength = 0.0;
        
        void recordHitDealt(double distance, long currentTime) {
            hitsDealt++;
            totalEngagements++;
            lastHitTime = currentTime;
            averageDistance = (averageDistance * (totalEngagements - 1) + distance) / totalEngagements;
        }
        
        void recordHitReceived(double knockback) {
            hitsReceived++;
            knockbackStrength = (knockbackStrength + knockback) / 2.0;
        }
        
        boolean isInCombo(long currentTime) {
            return (currentTime - lastHitTime) < 1000L && consecutiveComboHits >= 2;
        }
        
        void reset() {
            consecutiveComboHits = 0;
            combatStartTime = System.currentTimeMillis();
        }
    }

    /**
     * Checks if the player can currently trigger W-Tap
     */
    private boolean canTrigger() {
        if (mc.thePlayer == null) return false;
        
        // Check forward movement
        if (mc.thePlayer.movementInput.moveForward < 0.8F) return false;
        
        // Check collision
        if (mc.thePlayer.isCollidedHorizontally) return false;
        
        // Check hunger requirement
        if (requireHunger.getValue()) {
            if (mc.thePlayer.getFoodStats().getFoodLevel() <= 6 && !mc.thePlayer.capabilities.allowFlying) {
                return false;
            }
        }
        
        // Check sprinting requirement
        if (onlySprinting.getValue()) {
            boolean isSprinting = mc.thePlayer.isSprinting() || 
                (!mc.thePlayer.isUsingItem() && 
                 !mc.thePlayer.isPotionActive(Potion.blindness) && 
                 mc.gameSettings.keyBindSprint.isKeyDown());
            if (!isSprinting) return false;
        }
        
        return true;
    }

    /**
     * Checks if the entity is a valid target
     */
    private boolean isValidTarget(C02PacketUseEntity packet) {
        if (packet.getEntityFromWorld(mc.theWorld) instanceof EntityLivingBase) {
            EntityLivingBase entity = (EntityLivingBase) packet.getEntityFromWorld(mc.theWorld);
            
            // Check if only players is enabled
            if (onlyPlayers.getValue() && !(entity instanceof EntityPlayer)) {
                return false;
            }
            
            // Check if entity is alive
            if (entity.getHealth() <= 0) return false;
            
            return true;
        }
        return false;
    }

    /**
     * ADAPTIVE MODE - Analyzes combat situation and chooses best strategy
     */
    private String determineAdaptiveMode(EntityLivingBase target) {
        if (target == null || mc.thePlayer == null) return "Offensive";
        
        long currentTime = System.currentTimeMillis();
        int entityId = target.getEntityId();
        
        // Get or create combat data for this target
        CombatData data = targetDataMap.getOrDefault(entityId, new CombatData());
        
        // Calculate current distance
        double distance = mc.thePlayer.getDistanceToEntity(target);
        
        // Detect if we're being comboed (received multiple hits in short time)
        if (currentTime - lastHitReceivedTime < 500L) {
            consecutiveHitsReceived++;
            if (consecutiveHitsReceived >= adaptiveSensitivity.getValue()) {
                isBeingComboed = true;
            }
        } else {
            consecutiveHitsReceived = 0;
            isBeingComboed = false;
        }
        
        // Detect if we're successfully comboing them
        boolean weAreComboingThem = data.isInCombo(currentTime) && consecutiveHitsDealt >= 2;
        
        // PRIORITY 1: Defensive mode if being comboed and auto defensive enabled
        if (autoDefensive.getValue() && isBeingComboed) {
            if (adaptiveDebug.getValue()) {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    "§e[Adaptive] §cBeing comboed! Switching to DEFENSIVE"));
            }
            return "Defensive";
        }
        
        // PRIORITY 2: Detect Hypixel knockback pattern for Zest mode
        if (detectHypixel.getValue() && data.likelyHypixel) {
            if (adaptiveDebug.getValue() {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    "§e[Adaptive] §dHypixel detected! Using ZEST"));
            }
            return "Zest";
        }
        
        // Analyze knockback pattern to detect Hypixel
        if (detectHypixel.getValue() && data.hitsDealt >= 3) {
            // Hypixel has distinctive knockback on 3rd hit
            if (hitCounter == 2 && data.knockbackStrength > 0.5) {
                data.likelyHypixel = true;
                targetDataMap.put(entityId, data);
                return "Zest";
            }
        }
        
        // PRIORITY 3: Light mode for maintaining combos or high ping opponents
        if (preferLight.getValue()) {
            // Use Light if we're already comboing them
            if (weAreComboingThem) {
                if (adaptiveDebug.getValue()) {
                    mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                        "§e[Adaptive] §aCombo active! Using LIGHT"));
                }
                return "Light";
            }
            
            // Use Light if opponent seems laggy (inconsistent distance)
            if (data.totalEngagements >= 5) {
                double distanceVariance = Math.abs(distance - data.averageDistance);
                if (distanceVariance > 1.5) {
                    if (adaptiveDebug.getValue()) {
                        mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                            "§e[Adaptive] §6Laggy opponent! Using LIGHT"));
                    }
                    return "Light";
                }
            }
        }
        
        // PRIORITY 4: Smart mode - switch based on combat state
        // If we're losing the trade (receiving more hits)
        if (data.hitsReceived > data.hitsDealt && data.totalEngagements >= 3) {
            if (adaptiveDebug.getValue()) {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    "§e[Adaptive] §cLosing trade! Using DEFENSIVE"));
            }
            return "Defensive";
        }
        
        // If we're winning the trade (dealing more hits)
        if (data.hitsDealt > data.hitsReceived && data.totalEngagements >= 3) {
            if (adaptiveDebug.getValue()) {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    "§e[Adaptive] §aWinning trade! Using LIGHT"));
            }
            return preferLight.getValue() ? "Light" : "Offensive";
        }
        
        // DEFAULT: Offensive mode for first few hits or neutral situations
        if (adaptiveDebug.getValue() && data.totalEngagements == 0) {
            mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                "§e[Adaptive] §7First engagement - Using OFFENSIVE"));
        }
        
        return "Offensive";
    }

    /**
     * Activates W-Tap with specified delay and duration
     */
    private void activate(int delayMs, int durationMs) {
        // Check chance
        if (Math.random() * 100.0F > chance.getValue()) return;
        
        this.active = true;
        this.stopForward = false;
        this.delayTicks = delayMs / 50;
        this.durationTicks = durationMs / 50;
        this.attackTimer.reset();
    }

    /**
     * Resets the W-Tap state
     */
    private void reset() {
        this.active = false;
        this.stopForward = false;
        this.delayTicks = 0;
        this.durationTicks = 0;
    }

    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.active) return;
        
        // Smart reset - cancel if conditions are no longer met
        if (smartReset.getValue() && !this.stopForward && !this.canTrigger()) {
            reset();
            return;
        }
        
        // Handle delay phase
        if (this.delayTicks > 0) {
            this.delayTicks--;
            return;
        }
        
        // Handle duration phase - stop forward movement
        if (this.durationTicks > 0) {
            this.durationTicks--;
            this.stopForward = true;
            event.forward = 0.0F;
            mc.thePlayer.movementInput.moveForward = 0.0F;
            
            // Light W-Tap mode - very short duration
            String effectiveMode = mode.getValue().equals("Adaptive") ? currentAdaptiveMode : mode.getValue();
            if (effectiveMode.equals("Light") && this.durationTicks <= 0) {
                reset();
            }
        } else {
            reset();
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        // Increment ticks since last hit for combo tracking
        ticksSinceLastHit++;
        
        // Reset consecutive hits if too much time passed
        if (ticksSinceLastHit > 20) { // 1 second
            consecutiveHitsDealt = 0;
        }
        
        // Adaptive mode analysis timer
        if (mode.getValue().equals("Adaptive") && adaptiveAnalysisTimer.hasTimeElapsed(5000L)) {
            // Clean up old combat data every 5 seconds
            long currentTime = System.currentTimeMillis();
            targetDataMap.entrySet().removeIf(entry -> 
                currentTime - entry.getValue().lastHitTime > 10000L);
            adaptiveAnalysisTimer.reset();
        }
        
        // Zest tapping - check if target is falling
        String effectiveMode = mode.getValue().equals("Adaptive") ? currentAdaptiveMode : mode.getValue();
        if (effectiveMode.equals("Zest") && waitingForFall && lastTarget != null) {
            if (lastTarget.motionY < -0.08) {
                waitingForFall = false;
                hitCounter = 0;
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled()) return;
        
        // Handle outgoing attack packets
        if (event.getType() == EventType.SEND) {
            if (event.getPacket() instanceof C02PacketUseEntity) {
                C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
                
                if (packet.getAction() == Action.ATTACK && 
                    isValidTarget(packet) && 
                    !this.active && 
                    this.attackTimer.hasTimeElapsed((long) cooldown.getValue())) {
                    
                    EntityLivingBase target = (EntityLivingBase) packet.getEntityFromWorld(mc.theWorld);
                    long currentTime = System.currentTimeMillis();
                    
                    // Update combat tracking
                    double distance = mc.thePlayer.getDistanceToEntity(target);
                    lastDistance = distance;
                    
                    // Track consecutive hits for combo detection
                    if (currentTime - lastHitTime < 500L) {
                        consecutiveHitsDealt++;
                    } else {
                        consecutiveHitsDealt = 1;
                    }
                    lastHitTime = currentTime;
                    ticksSinceLastHit = 0;
                    
                    // Update target data for adaptive mode
                    int entityId = target.getEntityId();
                    CombatData data = targetDataMap.getOrDefault(entityId, new CombatData());
                    data.recordHitDealt(distance, currentTime);
                    if (consecutiveHitsDealt >= 2) {
                        data.consecutiveComboHits = consecutiveHitsDealt;
                    }
                    targetDataMap.put(entityId, data);
                    
                    // Determine which mode to use
                    String effectiveMode = mode.getValue();
                    if (effectiveMode.equals("Adaptive")) {
                        currentAdaptiveMode = determineAdaptiveMode(target);
                        effectiveMode = currentAdaptiveMode;
                    } else if (effectiveMode.equals("Smart")) {
                        // Smart mode uses simplified adaptive logic
                        if (isBeingComboed) {
                            effectiveMode = "Defensive";
                        } else if (consecutiveHitsDealt >= 2) {
                            effectiveMode = preferLight.getValue() ? "Light" : "Offensive";
                        } else {
                            effectiveMode = "Offensive";
                        }
                    }
                    
                    // Execute W-Tap based on determined mode
                    if (effectiveMode.equals("Offensive") || effectiveMode.equals("Light")) {
                        // Offensive/Light W-Tap - tap on attack
                        if (mc.thePlayer.isSprinting() || !onlySprinting.getValue()) {
                            int delayMs = (int) (delay.getValue() * 50.0F);
                            int durationMs = effectiveMode.equals("Light") ? 50 : (int) (duration.getValue() * 50.0F);
                            activate(delayMs, durationMs);
                        }
                    } else if (effectiveMode.equals("Zest")) {
                        // Zest tapping - special Hypixel technique
                        hitCounter++;
                        lastTarget = target;
                        
                        if (hitCounter >= 2 && !waitingForFall) {
                            // On third hit, wait for target to fall
                            waitingForFall = true;
                        } else if (hitCounter >= 2 && !target.onGround && target.motionY < -0.08) {
                            // Target is falling - do W-Tap
                            activate(0, (int) (duration.getValue() * 50.0F));
                            waitingForFall = false;
                            hitCounter = 0;
                        }
                    }
                }
            }
        }
        
        // Handle incoming velocity packets (for Defensive mode and combo detection)
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
                
                if (packet.getEntityID() == mc.thePlayer.getEntityId()) {
                    long currentTime = System.currentTimeMillis();
                    
                    // Calculate knockback strength
                    double velocityX = packet.getMotionX() / 8000.0;
                    double velocityY = packet.getMotionY() / 8000.0;
                    double velocityZ = packet.getMotionZ() / 8000.0;
                    double knockbackStrength = Math.sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ);
                    
                    // Update combat data for current target
                    if (lastTarget != null) {
                        int entityId = lastTarget.getEntityId();
                        CombatData data = targetDataMap.getOrDefault(entityId, new CombatData());
                        data.recordHitReceived(knockbackStrength);
                        targetDataMap.put(entityId, data);
                    }
                    
                    // Track hits received for combo detection
                    lastHitReceivedTime = currentTime;
                    
                    // Determine if we should use Defensive W-Tap
                    String effectiveMode = mode.getValue();
                    if (effectiveMode.equals("Adaptive")) {
                        effectiveMode = currentAdaptiveMode;
                    } else if (effectiveMode.equals("Smart")) {
                        effectiveMode = isBeingComboed ? "Defensive" : effectiveMode;
                    }
                    
                    if (effectiveMode.equals("Defensive") && 
                        !this.active &&
                        canTrigger() &&
                        this.hitTimer.hasTimeElapsed((long) cooldown.getValue())) {
                        
                        // Defensive W-Tap - tap immediately after being hit
                        int delayMs = (int) (delay.getValue() * 50.0F);
                        int durationMs = (int) (duration.getValue() * 50.0F);
                        activate(delayMs, durationMs);
                        this.hitTimer.reset();
                    }
                }
            }
        }
    }
    
    @Override
    public void onEnable() {
        reset();
        hitCounter = 0;
        waitingForFall = false;
        lastTarget = null;
        currentAdaptiveMode = "Offensive";
        targetDataMap.clear();
        consecutiveHitsDealt = 0;
        consecutiveHitsReceived = 0;
        isBeingComboed = false;
        ticksSinceLastHit = 0;
    }
    
    @Override
    public void onDisable() {
        reset();
        hitCounter = 0;
        waitingForFall = false;
        lastTarget = null;
        targetDataMap.clear();
        consecutiveHitsDealt = 0;
        consecutiveHitsReceived = 0;
        isBeingComboed = false;
    }
}
