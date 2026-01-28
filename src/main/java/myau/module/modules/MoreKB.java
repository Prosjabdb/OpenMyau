package myau.module.modules;

import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.NumberProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

/**
 * Advanced MoreKB Module - Enhanced knockback manipulation
 * Features:
 * - Multiple knockback modes with optimized packet handling
 * - Smart targeting with angle detection
 * - Configurable delays and conditions
 * - Anti-cheat bypass options
 * - Performance optimizations
 */
public class MoreKB extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // Mode Configuration
    public final ModeProperty mode = new ModeProperty("Mode", 2, new String[]{
        "LEGIT",           // Client-side sprint reset
        "LEGIT_FAST",      // Fast sprint tick manipulation
        "LESS_PACKET",     // Single packet sprint reset
        "PACKET",          // Double packet method
        "DOUBLE_PACKET",   // Quad packet method
        "HYBRID",          // Combines client and packet methods
        "ADAPTIVE"         // Auto-adjusts based on conditions
    });
    
    // Targeting Options
    public final BooleanProperty intelligent = new BooleanProperty("Intelligent", true);
    public final NumberProperty maxAngle = new NumberProperty("Max-Angle", 120.0, 60.0, 180.0, 5.0);
    public final BooleanProperty playersOnly = new BooleanProperty("Players-Only", false);
    
    // Conditions
    public final BooleanProperty onlyGround = new BooleanProperty("Only-Ground", true);
    public final BooleanProperty onlyMoving = new BooleanProperty("Only-Moving", false);
    public final BooleanProperty checkDistance = new BooleanProperty("Check-Distance", true);
    public final NumberProperty maxDistance = new NumberProperty("Max-Distance", 3.5, 3.0, 6.0, 0.1);
    
    // Timing Options
    public final NumberProperty hurtTime = new NumberProperty("Hurt-Time", 10, 8, 10, 1);
    public final NumberProperty delay = new NumberProperty("Delay", 0, 0, 5, 1);
    
    // Anti-Cheat Bypass
    public final BooleanProperty randomize = new BooleanProperty("Randomize", false);
    public final BooleanProperty verusMode = new BooleanProperty("Verus-Mode", false);
    public final BooleanProperty watchdogMode = new BooleanProperty("Watchdog-Mode", false);
    
    // Advanced Features
    public final BooleanProperty autoMode = new BooleanProperty("Auto-Mode-Switch", false);
    
    // Internal State
    private EntityLivingBase target;
    private EntityLivingBase lastTarget;
    private long lastAttackTime;
    private int ticksSinceAttack;
    private int successfulHits;
    private int totalAttempts;
    private boolean shouldProcess;
    
    public MoreKB() {
        super("MoreKB", false);
        this.target = null;
        this.lastTarget = null;
        this.lastAttackTime = 0L;
        this.ticksSinceAttack = 0;
        this.successfulHits = 0;
        this.totalAttempts = 0;
        this.shouldProcess = false;
    }
    
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
        this.target = null;
        this.lastTarget = null;
        this.ticksSinceAttack = 0;
        this.shouldProcess = false;
    }
    
    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || event.getTarget() == null) {
            return;
        }
        
        Entity targetEntity = event.getTarget();
        if (!(targetEntity instanceof EntityLivingBase)) {
            return;
        }
        
        // Player-only filter
        if (this.playersOnly.getValue() && !(targetEntity instanceof EntityPlayer)) {
            return;
        }
        
        this.target = (EntityLivingBase) targetEntity;
        this.lastTarget = this.target;
        this.lastAttackTime = System.currentTimeMillis();
        this.ticksSinceAttack = 0;
        this.shouldProcess = true;
        
        // LEGIT_FAST mode processes immediately on attack
        if (this.mode.getValue() == 1) {
            processLegitFastMode();
        }
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        
        this.ticksSinceAttack++;
        
        // Process current target
        if (this.shouldProcess && this.target != null) {
            processKnockback();
        }
        
        // Adaptive mode learning
        if (this.autoMode.getValue() && this.totalAttempts > 50) {
            optimizeMode();
        }
    }
    
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        
        // Raycast target detection for non-attack based modes
        if (mc.objectMouseOver != null && 
            mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            
            Entity entity = mc.objectMouseOver.entityHit;
            if (entity instanceof EntityLivingBase) {
                EntityLivingBase livingEntity = (EntityLivingBase) entity;
                
                // Additional validation
                if (isValidTarget(livingEntity)) {
                    processEntityKnockback(livingEntity);
                }
            }
        }
    }
    
    private void processLegitFastMode() {
        if (!canProcess()) {
            return;
        }
        
        if (this.target != null && isMoving()) {
            if ((this.onlyGround.getValue() && mc.thePlayer.onGround) || !this.onlyGround.getValue()) {
                // Fast sprint tick reset
                mc.thePlayer.sprintingTicksLeft = 0;
                mc.thePlayer.serverSprintState = false;
            }
            this.target = null;
            this.shouldProcess = false;
        }
    }
    
    private void processKnockback() {
        if (this.target == null || !canProcess()) {
            return;
        }
        
        // Check hurt time
        int targetHurtTime = (int) this.hurtTime.getValue();
        if (this.target.hurtTime != targetHurtTime) {
            return;
        }
        
        // Apply delay if configured
        if (this.delay.getValue() > 0 && this.ticksSinceAttack < this.delay.getValue()) {
            return;
        }
        
        // Randomization for anti-cheat bypass
        if (this.randomize.getValue() && Math.random() > 0.85) {
            return;
        }
        
        this.totalAttempts++;
        
        int currentMode = this.mode.getValue();
        boolean success = false;
        
        switch (currentMode) {
            case 0: // LEGIT
                success = applyLegitMode();
                break;
            case 1: // LEGIT_FAST (handled in onAttack)
                break;
            case 2: // LESS_PACKET
                success = applyLessPacketMode();
                break;
            case 3: // PACKET
                success = applyPacketMode();
                break;
            case 4: // DOUBLE_PACKET
                success = applyDoublePacketMode();
                break;
            case 5: // HYBRID
                success = applyHybridMode();
                break;
            case 6: // ADAPTIVE
                success = applyAdaptiveMode();
                break;
        }
        
        if (success) {
            this.successfulHits++;
        }
        
        this.shouldProcess = false;
    }
    
    private void processEntityKnockback(EntityLivingBase entity) {
        if (entity.hurtTime != (int) this.hurtTime.getValue()) {
            return;
        }
        
        if (!isValidTarget(entity)) {
            return;
        }
        
        this.target = entity;
        processKnockback();
    }
    
    private boolean applyLegitMode() {
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
            mc.thePlayer.setSprinting(true);
            return true;
        }
        return false;
    }
    
    private boolean applyLessPacketMode() {
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
        }
        sendSprintPacket(true);
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyPacketMode() {
        sendSprintPacket(false);
        sendSprintPacket(true);
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyDoublePacketMode() {
        sendSprintPacket(false);
        sendSprintPacket(true);
        sendSprintPacket(false);
        sendSprintPacket(true);
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyHybridMode() {
        // Combine client-side and packet methods
        mc.thePlayer.setSprinting(false);
        sendSprintPacket(false);
        sendSprintPacket(true);
        mc.thePlayer.setSprinting(true);
        mc.thePlayer.sprintingTicksLeft = 0;
        return true;
    }
    
    private boolean applyAdaptiveMode() {
        // Choose best mode based on conditions
        if (this.verusMode.getValue()) {
            return applyLessPacketMode();
        } else if (this.watchdogMode.getValue()) {
            return applyHybridMode();
        } else if (mc.thePlayer.onGround) {
            return applyPacketMode();
        } else {
            return applyLegitMode();
        }
    }
    
    private void optimizeMode() {
        double successRate = (double) this.successfulHits / this.totalAttempts;
        
        // Auto-adjust mode based on success rate
        if (successRate < 0.6) {
            // Try a different mode
            int newMode = (this.mode.getValue() + 1) % 7;
            this.mode.setValue(newMode);
            
            // Reset statistics
            this.successfulHits = 0;
            this.totalAttempts = 0;
        }
    }
    
    private boolean isValidTarget(EntityLivingBase entity) {
        if (entity == null || entity == mc.thePlayer) {
            return false;
        }
        
        // Player-only check
        if (this.playersOnly.getValue() && !(entity instanceof EntityPlayer)) {
            return false;
        }
        
        // Distance check
        if (this.checkDistance.getValue()) {
            double distance = mc.thePlayer.getDistanceToEntity(entity);
            if (distance > this.maxDistance.getValue()) {
                return false;
            }
        }
        
        // Angle check (intelligent targeting)
        if (this.intelligent.getValue()) {
            double x = mc.thePlayer.posX - entity.posX;
            double z = mc.thePlayer.posZ - entity.posZ;
            float calcYaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI - 90.0);
            float diffY = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - entity.rotationYawHead));
            
            if (diffY > this.maxAngle.getValue()) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean canProcess() {
        // Ground check
        if (this.onlyGround.getValue() && !mc.thePlayer.onGround) {
            return false;
        }
        
        // Movement check
        if (this.onlyMoving.getValue() && !isMoving()) {
            return false;
        }
        
        return true;
    }
    
    private void sendSprintPacket(boolean sprinting) {
        C0BPacketEntityAction.Action action = sprinting 
            ? C0BPacketEntityAction.Action.START_SPRINTING 
            : C0BPacketEntityAction.Action.STOP_SPRINTING;
        
        mc.thePlayer.sendQueue.addToSendQueue(
            new C0BPacketEntityAction(mc.thePlayer, action)
        );
    }
    
    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }
    
    public double getSuccessRate() {
        return this.totalAttempts > 0 
            ? (double) this.successfulHits / this.totalAttempts * 100.0 
            : 0.0;
    }
    
    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getOptions()[this.mode.getValue()]};
    }
                }
