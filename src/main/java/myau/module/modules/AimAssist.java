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
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class AimAssist extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil timer = new TimerUtil();
    public final FloatProperty hSpeed = new FloatProperty("Horizontal Speed", 3.0F, 0.0F, 10.0F);
    public final FloatProperty vSpeed = new FloatProperty("Vertical Speed", 0.0F, 0.0F, 10.0F);
    public final FloatProperty smoothing = new FloatProperty("Smoothing", 50.0F, 0.0F, 100.0F);
    public final FloatProperty range = new FloatProperty("Range", 4.5F, 3.0F, 8.0F);
    public final IntProperty fov = new IntProperty("FOV", 90, 30, 360);
    public final BooleanProperty weaponOnly = new BooleanProperty("Weapons Only", true);
    public final BooleanProperty allowTools = new BooleanProperty("Allow Tools", false);
    public final BooleanProperty botChecks = new BooleanProperty("Bot Checks", true);
    public final BooleanProperty team = new BooleanProperty("Teams", true);
    public final BooleanProperty prediction = new BooleanProperty("Prediction", true);
    public final FloatProperty randomization = new FloatProperty("Randomization", 0.5F, 0.0F, 2.0F);
    public final BooleanProperty onlyOnClick = new BooleanProperty("Only on Click", true); // New property

    private boolean isValidTarget(EntityPlayer entityPlayer) {
        if (entityPlayer == null || entityPlayer == mc.thePlayer || entityPlayer == mc.thePlayer.ridingEntity) {
            return false;
        }
        if (entityPlayer == mc.getRenderViewEntity() || entityPlayer == mc.getRenderViewEntity().ridingEntity) {
            return false;
        }
        if (entityPlayer.deathTime > 0) {
            return false;
        }
        if (RotationUtil.distanceToEntity(entityPlayer) > this.range.getValue()) {
            return false;
        }
        if (RotationUtil.angleToEntity(entityPlayer) > this.fov.getValue()) {
            return false;
        }
        if (RotationUtil.rayTrace(entityPlayer) != null) {
            return false;
        }
        if (TeamUtil.isFriend(entityPlayer)) {
            return false;
        }
        return (!this.team.getValue() || !TeamUtil.isSameTeam(entityPlayer)) && (!this.botChecks.getValue() || !TeamUtil.isBot(entityPlayer));
    }

    private boolean isInReach(EntityPlayer entityPlayer) {
        Reach reach = (Reach) Myau.moduleManager.modules.get(Reach.class);
        double distance = reach != null && reach.isEnabled() ? reach.range.getValue() : 3.0;
        return RotationUtil.distanceToEntity(entityPlayer) <= distance;
    }

    private boolean isLookingAtBlock() {
        return mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK;
    }

    public AimAssist() {
        super("AimAssist", false);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST || mc.currentScreen != null) {
            return;
        }
        if (this.weaponOnly.getValue() && !ItemUtil.hasRawUnbreakingEnchant() && (!this.allowTools.getValue() || !ItemUtil.isHoldingTool())) {
            return;
        }
        boolean attacking = PlayerUtil.isAttacking() || mc.gameSettings.keyBindAttack.isKeyDown();
        if (this.onlyOnClick.getValue() && !attacking) {
            return; // Only activate if clicking
        }
        if (attacking && this.isLookingAtBlock()) {
            return;
        }
        if (!attacking && !this.timer.hasTimeElapsed(350L)) {
            return;
        }
        List<EntityPlayer> inRange = mc.theWorld.loadedEntityList.stream()
                .filter(entity -> entity instanceof EntityPlayer)
                .map(entity -> (EntityPlayer) entity)
                .filter(this::isValidTarget)
                .sorted(Comparator.comparingDouble(RotationUtil::distanceToEntity))
                .collect(Collectors.toList());
        if (inRange.isEmpty()) {
            return;
        }
        if (inRange.stream().anyMatch(this::isInReach)) {
            inRange.removeIf(entityPlayer -> !this.isInReach(entityPlayer));
        }
        EntityPlayer player = inRange.get(0);
        if (RotationUtil.distanceToEntity(player) <= 0.0) {
            return;
        }
        AxisAlignedBB axisAlignedBB = player.getEntityBoundingBox();
        double collisionBorderSize = player.getCollisionBorderSize();
        float[] rotation = RotationUtil.getRotationsToBox(
                axisAlignedBB.expand(collisionBorderSize, collisionBorderSize, collisionBorderSize),
                mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch,
                180.0F,
                this.smoothing.getValue() / 100.0F
        );
        if (this.prediction.getValue()) {
            rotation[0] += (float) (player.motionX * 5.0);
            rotation[1] += (float) (player.motionY * 5.0);
        }
        float yaw = Math.min(Math.abs(this.hSpeed.getValue()), 10.0F) + (float) (Math.random() * this.randomization.getValue());
        float pitch = Math.min(Math.abs(this.vSpeed.getValue()), 10.0F) + (float) (Math.random() * this.randomization.getValue());
        Myau.rotationManager.setRotation(
                mc.thePlayer.rotationYaw + (rotation[0] - mc.thePlayer.rotationYaw) * 0.1F * yaw,
                mc.thePlayer.rotationPitch + (rotation[1] - mc.thePlayer.rotationPitch) * 0.1F * pitch,
                0,
                false
        );
    }

    @EventTarget
    public void onPress(KeyEvent event) {
        if (event.getKey() == mc.gameSettings.keyBindAttack.getKeyCode() && !Myau.moduleManager.modules.get(AutoClicker.class).isEnabled()) {
            this.timer.reset();
        }
    }
}
