package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.util.*;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.util.Random;

/**
 * Advanced AutoClicker Module
 * Provides human-like clicking with fatigue simulation, adaptive CPS, and smart patterns
 */
public class ImprovedAutoClicker extends Module {
    
    // ==================== Constants ====================
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random random = new Random();
    private static final int SPIKE_CHANCE = 5;
    private static final long FATIGUE_INTERVAL = 30000L;
    private static final long RECOVERY_INTERVAL = 10000L;
    
    // ==================== CPS Properties ====================
    public final IntProperty minCPS = new IntProperty("Min CPS", 8, 1, 20);
    public final IntProperty maxCPS = new IntProperty("Max CPS", 12, 1, 20);
    
    // ==================== Advanced Clicking ====================
    public final BooleanProperty humanLikePatterns = new BooleanProperty("Human Patterns", true);
    public final BooleanProperty butterflyMode = new BooleanProperty("Butterfly Mode", false);
    public final FloatProperty butterflyChance = new FloatProperty("Butterfly Chance", 30.0F, 0.0F, 100.0F);
    
    // ==================== Fatigue System ====================
    public final BooleanProperty enableFatigue = new BooleanProperty("Enable Fatigue", true);
    public final FloatProperty fatigueAmount = new FloatProperty("Fatigue Amount", 25.0F, 0.0F, 50.0F);
    public final BooleanProperty enableRecovery = new BooleanProperty("Enable Recovery", true);
    
    // ==================== CPS Variations ====================
    public final BooleanProperty cpsSpikes = new BooleanProperty("CPS Spikes", true);
    public final FloatProperty spikeMultiplier = new FloatProperty("Spike Multiplier", 1.5F, 1.0F, 3.0F);
    public final BooleanProperty cpsDrops = new BooleanProperty("CPS Drops", true);
    public final FloatProperty dropMultiplier = new FloatProperty("Drop Multiplier", 0.6F, 0.3F, 0.9F);
    
    // ==================== CPS Ramping ====================
    public final BooleanProperty enableRamping = new BooleanProperty("Enable Ramping", true);
    public final FloatProperty rampDuration = new FloatProperty("Ramp Duration", 1.0F, 0.5F, 3.0F);
    
    // ==================== Smart Activation ====================
    public final BooleanProperty smartActivation = new BooleanProperty("Smart Activation", true);
    public final IntProperty targetCPS = new IntProperty("Target CPS", 15, 8, 20);
    public final IntProperty idleCPS = new IntProperty("Idle CPS", 6, 1, 12);
    
    // ==================== Block Hit ====================
    public final BooleanProperty blockHit = new BooleanProperty("Block Hit", false);
    public final ModeProperty blockHitMode = new ModeProperty(
        "Block Hit Mode",
        "Smart",
        "Smart", "Alternating", "Random", "Burst"
    );
    public final FloatProperty blockHitChance = new FloatProperty("Block Hit Chance", 80.0F, 0.0F, 100.0F);
    public final IntProperty blockHitInterval = new IntProperty("Block Hit Interval", 2, 1, 5);
    
    // ==================== Item Restrictions ====================
    public final BooleanProperty weaponsOnly = new BooleanProperty("Weapons Only", true);
    public final BooleanProperty allowTools = new BooleanProperty("Allow Tools", false);
    
    // ==================== Block Breaking ====================
    public final BooleanProperty breakBlocks = new BooleanProperty("Break Blocks", true);
    public final FloatProperty range = new FloatProperty("Range", 3.0F, 3.0F, 8.0F);
    public final FloatProperty hitBoxVertical = new FloatProperty("Hitbox Vertical", 0.1F, 0.0F, 1.0F);
    public final FloatProperty hitBoxHorizontal = new FloatProperty("Hitbox Horizontal", 0.2F, 0.0F, 1.0F);
    
    // ==================== State Variables ====================
    private boolean clickPending = false;
    private long clickDelay = 0L;
    private boolean blockHitPending = false;
    
    // Fatigue system
    private long sessionStartTime = 0L;
    private long lastRecoveryTime = 0L;
    private boolean inFatigue = false;
    private float currentFatigueMultiplier = 1.0F;
    
    // CPS variation
    private long nextVariationChange = 0L;
    private float currentCPSMultiplier = 1.0F;
    private boolean isInSpike = false;
    
    // Ramping
    private long rampStartTime = 0L;
    private boolean isRamping = false;
    
    // Block-hit state
    private int clicksSinceBlockHit = 0;
    private boolean lastClickWasBlockHit = false;
    
    // Butterfly state
    private boolean nextClickIsDouble = false;
    
    // ==================== Constructor ====================
    public ImprovedAutoClicker() {
        super("AutoClicker", false);
    }
    
    // ==================== Core Logic ====================
    
    private int getEffectiveCPS() {
        int baseCPS;
        
        if (smartActivation.getValue() && hasValidTarget()) {
            baseCPS = targetCPS.getValue();
        } else if (smartActivation.getValue()) {
            baseCPS = idleCPS.getValue();
        } else {
            baseCPS = RandomUtil.nextInt(minCPS.getValue(), maxCPS.getValue());
        }
        
        float effectiveCPS = baseCPS;
        
        if (enableFatigue.getValue() && inFatigue) {
            effectiveCPS *= currentFatigueMultiplier;
        }
        
        effectiveCPS *= currentCPSMultiplier;
        
        if (isRamping && enableRamping.getValue()) {
            long timeSinceRampStart = System.currentTimeMillis() - rampStartTime;
            float rampProgress = Math.min(1.0F, timeSinceRampStart / (rampDuration.getValue() * 1000.0F));
            
            float rampMultiplier = 0.5F + (rampProgress * rampProgress * 0.5F);
            effectiveCPS *= rampMultiplier;
        }
        
        return Math.max(1, Math.round(effectiveCPS));
    }
    
    private long getNextClickDelay() {
        int effectiveCPS = getEffectiveCPS();
        
        long baseDelay = 1000L / effectiveCPS;
        
        if (humanLikePatterns.getValue()) {
            float randomFactor = 0.8F + (random.nextFloat() * 0.4F);
            baseDelay = Math.round(baseDelay * randomFactor);
        }
        
        if (butterflyMode.getValue() && random.nextFloat() * 100 < butterflyChance.getValue()) {
            nextClickIsDouble = true;
            return baseDelay / 2;
        }
        
        return Math.max(1L, baseDelay);
    }
    
    private void updateFatigue() {
        if (!enableFatigue.getValue()) {
            currentFatigueMultiplier = 1.0F;
            inFatigue = false;
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long sessionDuration = currentTime - sessionStartTime;
        
        if (!inFatigue && sessionDuration > FATIGUE_INTERVAL) {
            inFatigue = true;
            lastRecoveryTime = currentTime;
            
            float fatiguePercent = fatigueAmount.getValue() / 100.0F;
            currentFatigueMultiplier = 1.0F - fatiguePercent;
        }
        
        if (inFatigue && enableRecovery.getValue()) {
            long timeSinceRecovery = currentTime - lastRecoveryTime;
            
            if (timeSinceRecovery > RECOVERY_INTERVAL) {
                float recoveryProgress = Math.min(1.0F, timeSinceRecovery / (RECOVERY_INTERVAL * 2.0F));
                float fatiguePercent = fatigueAmount.getValue() / 100.0F;
                currentFatigueMultiplier = 1.0F - (fatiguePercent * (1.0F - recoveryProgress));
                
                if (recoveryProgress >= 1.0F) {
                    inFatigue = false;
                    currentFatigueMultiplier = 1.0F;
                    sessionStartTime = currentTime;
                }
            }
        }
    }
    
    private void updateCPSVariations() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime >= nextVariationChange) {
            long nextInterval = 3000L + random.nextInt(5000);
            nextVariationChange = currentTime + nextInterval;
            
            if (random.nextInt(SPIKE_CHANCE) == 0) {
                if (random.nextBoolean() && cpsSpikes.getValue()) {
                    currentCPSMultiplier = spikeMultiplier.getValue();
                    isInSpike = true;
                } else if (cpsDrops.getValue()) {
                    currentCPSMultiplier = dropMultiplier.getValue();
                    isInSpike = false;
                }
            } else {
                currentCPSMultiplier = 1.0F;
                isInSpike = false;
            }
        }
    }
    
    private boolean shouldBlockHit() {
        if (!blockHit.getValue()) {
            return false;
        }
        
        if (!ItemUtil.isHoldingSword()) {
            return false;
        }
        
        if (!mc.gameSettings.keyBindUseItem.isKeyDown()) {
            return false;
        }
        
        if (mc.thePlayer.isUsingItem()) {
            return false;
        }
        
        String mode = blockHitMode.getValue();
        
        switch (mode) {
            case "Smart":
                if (!hasValidTarget()) {
                    return false;
                }
                return random.nextFloat() * 100 < blockHitChance.getValue();
                
            case "Alternating":
                clicksSinceBlockHit++;
                if (clicksSinceBlockHit >= blockHitInterval.getValue()) {
                    clicksSinceBlockHit = 0;
                    return true;
                }
                return false;
                
            case "Random":
                return random.nextFloat() * 100 < blockHitChance.getValue();
                
            case "Burst":
                if (lastClickWasBlockHit) {
                    return random.nextFloat() < 0.6F;
                } else {
                    return random.nextFloat() * 100 < blockHitChance.getValue();
                }
                
            default:
                return false;
        }
    }
    
    private void performBlockHit() {
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindUseItem.getKeyCode());
        lastClickWasBlockHit = true;
    }
    
    private boolean isBreakingBlock() {
        return mc.objectMouseOver != null 
            && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK;
    }
    
    private boolean canClick() {
        if (weaponsOnly.getValue()) {
            if (!ItemUtil.hasRawUnbreakingEnchant() 
                && !(allowTools.getValue() && ItemUtil.isHoldingTool())) {
                return false;
            }
        }
        
        if (breakBlocks.getValue() && isBreakingBlock() && !hasValidTarget()) {
            GameType gameType = mc.playerController.getCurrentGameType();
            return gameType != GameType.SURVIVAL && gameType != GameType.CREATIVE;
        }
        
        return true;
    }
    
    private boolean isValidTarget(EntityPlayer target) {
        if (target == mc.thePlayer || target == mc.thePlayer.ridingEntity) {
            return false;
        }
        
        if (target == mc.getRenderViewEntity() 
            || target == mc.getRenderViewEntity().ridingEntity) {
            return false;
        }
        
        if (target.deathTime > 0) {
            return false;
        }
        
        float borderSize = target.getCollisionBorderSize();
        return RotationUtil.rayTrace(
            target.getEntityBoundingBox().expand(
                borderSize + hitBoxHorizontal.getValue(),
                borderSize + hitBoxVertical.getValue(),
                borderSize + hitBoxHorizontal.getValue()
            ),
            mc.thePlayer.rotationYaw,
            mc.thePlayer.rotationPitch,
            range.getValue()
        ) != null;
    }
    
    private boolean hasValidTarget() {
        return mc.theWorld.loadedEntityList.stream()
            .filter(e -> e instanceof EntityPlayer)
            .map(e -> (EntityPlayer) e)
            .anyMatch(this::isValidTarget);
    }
    
    // ==================== Event Handlers ====================
    
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        
        if (clickDelay > 0L) {
            clickDelay -= 50L;
        }
        
        if (mc.currentScreen != null) {
            clickPending = false;
            blockHitPending = false;
            return;
        }
        
        if (clickPending) {
            clickPending = false;
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindAttack.getKeyCode());
        }
        
        if (blockHitPending) {
            blockHitPending = false;
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
        
        if (!isEnabled()) {
            return;
        }
        
        if (!canClick()) {
            return;
        }
        
        if (!mc.gameSettings.keyBindAttack.isKeyDown()) {
            isRamping = false;
            return;
        }
        
        if (mc.thePlayer.isUsingItem()) {
            return;
        }
        
        if (!isRamping && enableRamping.getValue()) {
            if (random.nextInt(5) == 0) {
                isRamping = true;
                rampStartTime = System.currentTimeMillis();
            }
        }
        
        updateFatigue();
        updateCPSVariations();
        
        while (clickDelay <= 0L) {
            clickPending = true;
            
            long nextDelay = getNextClickDelay();
            clickDelay += nextDelay;
            
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindAttack.getKeyCode());
            
            if (shouldBlockHit()) {
                blockHitPending = true;
                performBlockHit();
            } else {
                lastClickWasBlockHit = false;
            }
            
            if (nextClickIsDouble) {
                nextClickIsDouble = false;
                break;
            }
        }
    }
    
    @EventTarget(Priority.LOWEST)
    public void onClick(LeftClickMouseEvent event) {
        if (!isEnabled() || event.isCancelled()) {
            return;
        }
        
        if (!clickPending) {
            clickDelay += getNextClickDelay();
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
        clickDelay = 0L;
        clickPending = false;
        blockHitPending = false;
        
        sessionStartTime = System.currentTimeMillis();
        lastRecoveryTime = System.currentTimeMillis();
        inFatigue = false;
        currentFatigueMultiplier = 1.0F;
        
        nextVariationChange = System.currentTimeMillis() + 3000L;
        currentCPSMultiplier = 1.0F;
        isInSpike = false;
        
        rampStartTime = 0L;
        isRamping = false;
        
        clicksSinceBlockHit = 0;
        lastClickWasBlockHit = false;
        
        nextClickIsDouble = false;
    }
    
    @Override
    public void verifyValue(String property) {
        if (minCPS.getName().equals(property)) {
            if (minCPS.getValue() > maxCPS.getValue()) {
                maxCPS.setValue(minCPS.getValue());
            }
        } else if (maxCPS.getName().equals(property)) {
            if (minCPS.getValue() > maxCPS.getValue()) {
                minCPS.setValue(maxCPS.getValue());
            }
        }
    }
    
    @Override
    public String[] getSuffix() {
        int effectiveCPS = getEffectiveCPS();
        
        String state = "";
        if (isInSpike) {
            state = " ↑";
        } else if (currentCPSMultiplier < 1.0F) {
            state = " ↓";
        } else if (inFatigue) {
            state = " ~";
        }
        
        return new String[]{effectiveCPS + state};
    }
    }
