package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

import java.util.Random;

/**
 * Ultimate MoreKB Module
 * Maximum knockback with intelligent anti-cheat bypass and module integration
 */
public class ImprovedMoreKB extends Module {
    
    // ==================== Constants ====================
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random random = new Random();
    
    // ==================== Mode Configuration ====================
    public final ModeProperty mode = new ModeProperty(
        "Mode",
        "Adaptive",
        "Legit", "Legit-Fast", "Packet", "Double-Packet", "Hybrid", "Adaptive", "Aggressive"
    );
    
    public final ModeProperty antiCheatProfile = new ModeProperty(
        "Anti-Cheat Profile",
        "Universal",
        "Universal", "Watchdog", "Polar", "Intave", "AGC", "Verus", "NCP", "Vanilla"
    );
    
    // ==================== Knockback Strength ====================
    public final FloatProperty strengthMultiplier = new FloatProperty("Strength Multiplier", 1.0F, 0.5F, 2.0F);
    public final BooleanProperty extraPackets = new BooleanProperty("Extra Packets", false);
    public final IntProperty extraPacketCount = new IntProperty("Extra Packet Count", 1, 1, 3);
    
    // ==================== Combo System ====================
    public final BooleanProperty comboMode = new BooleanProperty("Combo Mode", true);
    public final FloatProperty comboKBMultiplier = new FloatProperty("Combo KB Multiplier", 1.3F, 1.0F, 2.0F);
    public final FloatProperty tradeKBMultiplier = new FloatProperty("Trade KB Multiplier", 0.8F, 0.5F, 1.5F);
    public final IntProperty comboThreshold = new IntProperty("Combo Threshold", 2, 1, 5);
    
    // ==================== Targeting Options ====================
    public final BooleanProperty intelligent = new BooleanProperty("Intelligent Targeting", true);
    public final FloatProperty maxAngle = new FloatProperty("Max Angle", 120.0F, 60.0F, 180.0F);
    public final BooleanProperty playersOnly = new BooleanProperty("Players Only", false);
    
    // ==================== Conditions ====================
    public final BooleanProperty onlyGround = new BooleanProperty("Only On Ground", true);
    public final BooleanProperty onlyMoving = new BooleanProperty("Only While Moving", false);
    public final BooleanProperty checkDistance = new BooleanProperty("Check Distance", true);
    public final FloatProperty maxDistance = new FloatProperty("Max Distance", 3.5F, 3.0F, 6.0F);
    
    // ==================== Timing Options ====================
    public final IntProperty hurtTime = new IntProperty("Hurt Time", 10, 8, 10);
    public final IntProperty delayTicks = new IntProperty("Delay Ticks", 0, 0, 5);
    
    // ==================== Anti-Cheat Bypass ====================
    public final BooleanProperty randomization = new BooleanProperty("Randomization", true);
    public final FloatProperty randomChance = new FloatProperty("Random Chance", 15.0F, 0.0F, 50.0F);
    public final BooleanProperty adaptiveDelay = new BooleanProperty("Adaptive Delay", true);
    public final BooleanProperty silentFail = new BooleanProperty("Silent Fail", true);
    
    // ==================== Module Integration ====================
    public final BooleanProperty integrateWTap = new BooleanProperty("Integrate W-Tap", true);
    public final BooleanProperty integrateHitSelect = new BooleanProperty("Integrate HitSelect", true);
    public final BooleanProperty respectAimAssist = new BooleanProperty("Respect AimAssist", true);
    
    // ==================== Auto-Disable ====================
    public final BooleanProperty autoDisable = new BooleanProperty("Auto Disable", true);
    public final BooleanProperty disableOnLowHealth = new BooleanProperty("Disable On Low Health", true);
    public final FloatProperty lowHealthThreshold = new FloatProperty("Low Health Threshold", 6.0F, 1.0F, 10.0F);
    public final BooleanProperty disableOnVelocity = new BooleanProperty("Disable On High Velocity", true);
    public final BooleanProperty disableOnScaffold = new BooleanProperty("Disable On Scaffold", true);
    
    // ==================== Internal State ====================
    private EntityLivingBase currentTarget;
    private EntityLivingBase lastTarget;
    private long lastAttackTime;
    private int ticksSinceAttack;
    private boolean shouldProcess;
    
    // Combo tracking
    private int consecutiveHits;
    private long lastHitTime;
    private boolean isInCombo;
    private boolean isTrading;
    
    // Performance tracking
    private int successfulApplications;
    private int totalAttempts;
    
    // Anti-cheat adaptation
    private long lastPacketTime;
    private int packetDelay;
    
    // Module references (cached for performance)
    private Module wtapModule;
    private Module hitSelectModule;
    private Module aimAssistModule;
    
    // ==================== Constructor ====================
    public ImprovedMoreKB() {
        super("MoreKB", false);
        resetState();
    }
    
    // ==================== Module Lifecycle ====================
    
    @Override
    public void onEnable() {
        super.onEnable();
        resetState();
        cacheModuleReferences();
    }
    
    @Override
    public void onDisable() {
        super.onDisable();
        resetState();
    }
    
    private void resetState() {
        currentTarget = null;
        lastTarget = null;
        lastAttackTime = 0L;
        ticksSinceAttack = 0;
        shouldProcess = false;
        consecutiveHits = 0;
        lastHitTime = 0L;
        isInCombo = false;
        isTrading = false;
        successfulApplications = 0;
        totalAttempts = 0;
        lastPacketTime = 0L;
        packetDelay = 0;
    }
    
    private void cacheModuleReferences() {
        try {
            wtapModule = Myau.moduleManager.modules.get(ImprovedWtap.class);
            if (wtapModule == null) {
                wtapModule = Myau.moduleManager.modules.get(Wtap.class);
            }
        } catch (Exception e) {
            wtapModule = null;
        }
        
        try {
            hitSelectModule = Myau.moduleManager.modules.get(ImprovedHitSelect.class);
            if (hitSelectModule == null) {
                hitSelectModule = Myau.moduleManager.modules.get(HitSelect.class);
            }
        } catch (Exception e) {
            hitSelectModule = null;
        }
        
        try {
            aimAssistModule = Myau.moduleManager.modules.get(ImprovedAimAssist.class);
            if (aimAssistModule == null) {
                aimAssistModule = Myau.moduleManager.modules.get(AimAssist.class);
            }
        } catch (Exception e) {
            aimAssistModule = null;
        }
    }
    
    // ==================== Event Handlers ====================
    
    @EventTarget(Priority.HIGH)
    public void onAttack(AttackEvent event) {
        if (!isEnabled() || event.getTarget() == null) {
            return;
        }
        
        Entity targetEntity = event.getTarget();
        if (!(targetEntity instanceof EntityLivingBase)) {
            return;
        }
        
        if (playersOnly.getValue() && !(targetEntity instanceof EntityPlayer)) {
            return;
        }
        
        EntityLivingBase target = (EntityLivingBase) targetEntity;
        
        updateComboState(target);
        
        currentTarget = target;
        lastTarget = target;
        lastAttackTime = System.currentTimeMillis();
        ticksSinceAttack = 0;
        shouldProcess = true;
        
        if (shouldAutoDisable()) {
            return;
        }
        
        String currentMode = mode.getValue();
        if (currentMode.equals("Legit-Fast") || currentMode.equals("Aggressive")) {
            processKnockbackImmediate(target);
        }
    }
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled()) {
            return;
        }
        
        ticksSinceAttack++;
        
        updateComboTimeout();
        
        if (shouldProcess && currentTarget != null) {
            processKnockbackDelayed();
        }
        
        if (ticksSinceAttack > 20) {
            currentTarget = null;
            shouldProcess = false;
        }
    }
    
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled()) {
            return;
        }
        
        if (mc.objectMouseOver != null && 
            mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            
            Entity entity = mc.objectMouseOver.entityHit;
            if (entity instanceof EntityLivingBase) {
                EntityLivingBase livingEntity = (EntityLivingBase) entity;
                
                if (isValidTarget(livingEntity) && shouldProcessEntity(livingEntity)) {
                    processEntityKnockback(livingEntity);
                }
            }
        }
    }
    
    // ==================== Combo System ====================
    
    private void updateComboState(EntityLivingBase target) {
        long currentTime = System.currentTimeMillis();
        
        if (target == lastTarget && currentTime - lastHitTime < 1500L) {
            consecutiveHits++;
            isInCombo = consecutiveHits >= comboThreshold.getValue();
        } else {
            consecutiveHits = 1;
            isInCombo = false;
        }
        
        lastHitTime = currentTime;
        
        if (mc.thePlayer.hurtTime > 0 && mc.thePlayer.hurtTime <= 5) {
            isTrading = true;
        } else {
            isTrading = false;
        }
    }
    
    private void updateComboTimeout() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastHitTime > 1500L) {
            consecutiveHits = 0;
            isInCombo = false;
        }
    }
    
    private float getKnockbackMultiplier() {
        float baseMultiplier = strengthMultiplier.getValue();
        
        if (!comboMode.getValue()) {
            return baseMultiplier;
        }
        
        if (isInCombo) {
            baseMultiplier *= comboKBMultiplier.getValue();
        } else if (isTrading) {
            baseMultiplier *= tradeKBMultiplier.getValue();
        }
        
        return baseMultiplier;
    }
    
    // ==================== Module Integration ====================
    
    private boolean isModuleActive(Module module) {
        return module != null && module.isEnabled();
    }
    
    // ==================== Auto-Disable System ====================
    
    private boolean shouldAutoDisable() {
        if (!autoDisable.getValue()) {
            return false;
        }
        
        if (disableOnLowHealth.getValue()) {
            float health = mc.thePlayer.getHealth();
            if (health <= lowHealthThreshold.getValue()) {
                return true;
            }
        }
        
        if (disableOnVelocity.getValue()) {
            double velocity = Math.sqrt(
                mc.thePlayer.motionX * mc.thePlayer.motionX +
                mc.thePlayer.motionY * mc.thePlayer.motionY +
                mc.thePlayer.motionZ * mc.thePlayer.motionZ
            );
            if (velocity > 0.8) {
                return true;
            }
        }
        
        if (disableOnScaffold.getValue()) {
            if (mc.thePlayer.rotationPitch > 70.0F && mc.thePlayer.getHeldItem() != null) {
                return true;
            }
        }
        
        return false;
    }
    
    // ==================== Validation ====================
    
    private boolean isValidTarget(EntityLivingBase entity) {
        if (entity == null || entity == mc.thePlayer) {
            return false;
        }
        
        if (entity.isDead || entity.getHealth() <= 0) {
            return false;
        }
        
        if (playersOnly.getValue() && !(entity instanceof EntityPlayer)) {
            return false;
        }
        
        if (checkDistance.getValue()) {
            double distance = mc.thePlayer.getDistanceToEntity(entity);
            if (distance > maxDistance.getValue()) {
                return false;
            }
        }
        
        if (intelligent.getValue()) {
            double x = mc.thePlayer.posX - entity.posX;
            double z = mc.thePlayer.posZ - entity.posZ;
            float calcYaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI - 90.0);
            float diffY = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - entity.rotationYawHead));
            
            if (diffY > maxAngle.getValue()) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean canProcessKnockback() {
        if (onlyGround.getValue() && !mc.thePlayer.onGround) {
            return false;
        }
        
        if (onlyMoving.getValue() && !isMoving()) {
            return false;
        }
        
        return true;
    }
    
    private boolean shouldProcessEntity(EntityLivingBase entity) {
        if (entity.hurtTime != hurtTime.getValue()) {
            return false;
        }
        
        return canProcessKnockback();
    }
    
    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }
    
    // ==================== Knockback Application ====================
    
    private void processKnockbackImmediate(EntityLivingBase target) {
        if (!canProcessKnockback()) {
            return;
        }
        
        applyKnockback();
    }
    
    private void processKnockbackDelayed() {
        if (currentTarget == null || !canProcessKnockback()) {
            return;
        }
        
        if (currentTarget.hurtTime != hurtTime.getValue()) {
            return;
        }
        
        if (delayTicks.getValue() > 0 && ticksSinceAttack < delayTicks.getValue()) {
            return;
        }
        
        if (adaptiveDelay.getValue() && !hasAdaptiveDelayPassed()) {
            return;
        }
        
        if (randomization.getValue()) {
            if (random.nextFloat() * 100.0F < randomChance.getValue()) {
                shouldProcess = false;
                return;
            }
        }
        
        applyKnockback();
        shouldProcess = false;
    }
    
    private void processEntityKnockback(EntityLivingBase entity) {
        if (!shouldProcessEntity(entity)) {
            return;
        }
        
        currentTarget = entity;
        applyKnockback();
    }
    
    private void applyKnockback() {
        totalAttempts++;
        
        String currentMode = mode.getValue();
        String acProfile = antiCheatProfile.getValue();
        
        boolean success = false;
        
        if (currentMode.equals("Adaptive")) {
            success = applyAdaptiveMode(acProfile);
        } else {
            success = applyModeKnockback(currentMode);
        }
        
        if (success && extraPackets.getValue()) {
            applyExtraPackets();
        }
        
        if (success) {
            successfulApplications++;
        }
    }
    
    private boolean applyAdaptiveMode(String acProfile) {
        switch (acProfile) {
            case "Watchdog":
                return applyWatchdogBypass();
            case "Polar":
                return applyPolarBypass();
            case "Intave":
                return applyIntaveBypass();
            case "AGC":
                return applyAGCBypass();
            case "Verus":
                return applyVerusBypass();
            case "NCP":
                return applyNCPBypass();
            case "Vanilla":
                return applyVanillaMode();
            case "Universal":
            default:
                return applyUniversalMode();
        }
    }
    
    private boolean applyModeKnockback(String mode) {
        switch (mode) {
            case "Legit":
                return applyLegitMode();
            case "Legit-Fast":
                return applyLegitFastMode();
            case "Packet":
                return applyPacketMode();
            case "Double-Packet":
                return applyDoublePacketMode();
            case "Hybrid":
                return applyHybridMode();
            case "Aggressive":
                return applyAggressiveMode();
            default:
                return applyUniversalMode();
        }
    }
    
    // ==================== Mode Implementations ====================
    
    private boolean applyLegitMode() {
        if (mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
            mc.thePlayer.setSprinting(true);
            return true;
        }
        return false;
    }
    
    private boolean applyLegitFastMode() {
        if (isMoving()) {
            if ((onlyGround.getValue() && mc.thePlayer.onGround) || !onlyGround.getValue()) {
                mc.thePlayer.sprintingTicksLeft = 0;
                mc.thePlayer.serverSprintState = false;
                mc.thePlayer.setSprinting(true);
                return true;
            }
        }
        return false;
    }
    
    private boolean applyPacketMode() {
        sendSprintPacket(false);
        sendSprintPacket(true);
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyDoublePacketMode() {
        float multiplier = getKnockbackMultiplier();
        int packetCount = Math.round(2 * multiplier);
        
        for (int i = 0; i < packetCount; i++) {
            sendSprintPacket(false);
            sendSprintPacket(true);
        }
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyHybridMode() {
        mc.thePlayer.setSprinting(false);
        mc.thePlayer.sprintingTicksLeft = 0;
        
        sendSprintPacket(false);
        sendSprintPacket(true);
        
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyAggressiveMode() {
        float multiplier = getKnockbackMultiplier();
        int packets = Math.round(3 * multiplier);
        
        for (int i = 0; i < packets; i++) {
            sendSprintPacket(false);
            sendSprintPacket(true);
        }
        
        mc.thePlayer.sprintingTicksLeft = 0;
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    // ==================== Anti-Cheat Bypasses ====================
    
    private boolean applyWatchdogBypass() {
        if (packetDelay < 1) {
            packetDelay = 1;
            return false;
        }
        
        packetDelay = 0;
        
        sendSprintPacket(false);
        sendSprintPacket(true);
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyPolarBypass() {
        if (!mc.thePlayer.onGround) {
            return false;
        }
        
        mc.thePlayer.setSprinting(false);
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyIntaveBypass() {
        if (packetDelay < 2) {
            packetDelay++;
            return false;
        }
        
        packetDelay = 0;
        
        sendSprintPacket(false);
        sendSprintPacket(true);
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyAGCBypass() {
        mc.thePlayer.setSprinting(false);
        sendSprintPacket(false);
        sendSprintPacket(true);
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyVerusBypass() {
        sendSprintPacket(false);
        sendSprintPacket(true);
        sendSprintPacket(false);
        sendSprintPacket(true);
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    private boolean applyNCPBypass() {
        return applyDoublePacketMode();
    }
    
    private boolean applyVanillaMode() {
        return applyAggressiveMode();
    }
    
    private boolean applyUniversalMode() {
        if (!mc.thePlayer.onGround && silentFail.getValue()) {
            return false;
        }
        
        sendSprintPacket(false);
        sendSprintPacket(true);
        mc.thePlayer.setSprinting(true);
        return true;
    }
    
    // ==================== Helper Methods ====================
    
    private void applyExtraPackets() {
        int extraCount = extraPacketCount.getValue();
        for (int i = 0; i < extraCount; i++) {
            sendSprintPacket(false);
            sendSprintPacket(true);
        }
    }
    
    private void sendSprintPacket(boolean sprinting) {
        long currentTime = System.currentTimeMillis();
        lastPacketTime = currentTime;
        
        C0BPacketEntityAction.Action action = sprinting 
            ? C0BPacketEntityAction.Action.START_SPRINTING 
            : C0BPacketEntityAction.Action.STOP_SPRINTING;
        
        mc.thePlayer.sendQueue.addToSendQueue(
            new C0BPacketEntityAction(mc.thePlayer, action)
        );
    }
    
    private boolean hasAdaptiveDelayPassed() {
        String acProfile = antiCheatProfile.getValue();
        
        int requiredDelay = 0;
        switch (acProfile) {
            case "Watchdog":
            case "Polar":
                requiredDelay = 1;
                break;
            case "Intave":
                requiredDelay = 2;
                break;
            case "AGC":
            case "Verus":
                requiredDelay = 0;
                break;
            default:
                requiredDelay = 0;
        }
        
        return ticksSinceAttack >= requiredDelay;
    }
    
    // ==================== Statistics ====================
    
    public double getSuccessRate() {
        return totalAttempts > 0 
            ? (double) successfulApplications / totalAttempts * 100.0 
            : 0.0;
    }
    
    public int getComboLength() {
        return consecutiveHits;
    }
    
    public boolean isInCombo() {
        return isInCombo;
    }
    
    @Override
    public String[] getSuffix() {
        StringBuilder suffix = new StringBuilder();
        
        suffix.append(mode.getValue());
        
        if (isInCombo) {
            suffix.append(" [").append(consecutiveHits).append("x]");
        }
        
        if (isTrading) {
            suffix.append(" ⇄");
        }
        
        return new String[]{suffix.toString()};
    }
                }
