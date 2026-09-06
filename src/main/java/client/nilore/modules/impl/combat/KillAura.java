package client.nilore.modules.impl.combat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.hud.ModuleListHud;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.antikb.NoXZMode;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.AntiTNT;
import client.nilore.modules.impl.player.AntiWeb;
import client.nilore.modules.impl.player.AutoWebPlace;
import client.nilore.modules.impl.player.Helper;
import client.nilore.modules.impl.player.MidPearl;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.modules.impl.world.Teams;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.EntityUtil;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.math.MathUtil;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.utils.misc.Assets;
import client.nilore.utils.render.RenderUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;
import client.nilore.event.EventTarget;

public class KillAura extends Module {
    public static KillAura INSTANCE;
    public static Entity target;
    public static Entity aimingTarget;
    public static List<Entity> targetList = new ArrayList<>();

    private static final ResourceLocation NURIK_CAPTURE_TEXTURE = ResourceLocation.tryParse("nilore:nurik/capture");
    private static final String NURIK_CAPTURE_ASSET = "/assets/nilore/nurik/capture.png";
    private static boolean nurikTextureLoaded;
    private static boolean nurikTextureLoadFailed;

    // Fields kept in sync with the obfuscated jar: 13 BooleanSetting / 7
    // NumberSetting / 3 ModeSetting, in declaration order.
    public final BooleanSetting attackPlayer    = new BooleanSetting("Attack Player", true);
    public final BooleanSetting attackInvisible = new BooleanSetting("Attack Invisible", true);
    public final BooleanSetting attackAnimals   = new BooleanSetting("Attack Animals", false);
    public final BooleanSetting attackMobs      = new BooleanSetting("Attack Mobs", false);
    public final BooleanSetting multiAttack     = new BooleanSetting("Multi Attack", false);
    public final BooleanSetting infSwitch       = new BooleanSetting("Infinity Switch", false);
    public final BooleanSetting preferBaby      = new BooleanSetting("Prefer Baby", false);
    public final BooleanSetting morePart        = new BooleanSetting("More Particles", false);
    public final BooleanSetting keepSprint = new BooleanSetting("Keep Sprint", true);
    public final ModeSetting style = new ModeSetting("Style", "New", "Old", "onTickRot").withDefault("New");

    public final BooleanSetting fix             = new BooleanSetting("Fix", false,
            () -> this.style.is("Old"));
    public final BooleanSetting overrideRaycast = new BooleanSetting("Override Raycast", true,
            () -> this.style.is("Old") || this.style.is("onTickRot"));
    public final BooleanSetting throughWalls    = new BooleanSetting("Through Walls", false);
    public final NumberSetting throughWallsRange = new NumberSetting("Through Walls Range", 3.0, 1.0, 6.0, 0.1,
            () -> (Boolean) this.throughWalls.getValue());
    public final BooleanSetting ignoreSkipTicks = new BooleanSetting("Ignore skip ticks", false);
    public final BooleanSetting fakeAutoBlock   = new BooleanSetting("Fake AutoBlock", true);
    public final BooleanSetting test            = new BooleanSetting("Test", false);
    public final NumberSetting aimRange    = new NumberSetting("Aim Range", 3.0, 1.0, 6.0, 0.1);
    public final NumberSetting maxAps      = new NumberSetting("Max APS", 12.0, 1.0, 20.0, 1.0);
    public final NumberSetting minAps      = new NumberSetting("Min APS", 9.0, 1.0, 20.0, 1.0);
    public final NumberSetting switchSize  = new NumberSetting("Switch Size", 1.0, 1.0, 5.0, 1.0,
            () -> !(Boolean) this.infSwitch.getValue());
    public final NumberSetting switchDelay = new NumberSetting("Switch Delay (Attack Times)", 1.0, 1.0, 10.0, 1.0);
    public final NumberSetting fov         = new NumberSetting("FoV", 360.0, 10.0, 360.0, 1.0);
    public final NumberSetting hurtTime    = new NumberSetting("Hurt Time", 10.0, 0.0, 10.0, 1.0);
    public final ModeSetting delayMode    = new ModeSetting("Delay Mode", "1.8", "1.9").withDefault("1.8");

    public final ModeSetting priorityMode = new ModeSetting("Priority", "Distance", "FoV", "Health", "None").withDefault("FoV");
    public final ModeSetting targetEsp    = new ModeSetting("Target ESP", "None", "Spiral", "Box", "Tab", "NurikZapen").withDefault("NurikZapen");

    public final BooleanSetting predictionEnabled  = new BooleanSetting("Prediction", true);
    public final NumberSetting enemyDelayThreshold = new NumberSetting("Enemy Delay Ticks", 4, 1, 5, 1,
            () -> (Boolean) this.predictionEnabled.getValue());
    public final NumberSetting selfDelayThreshold  = new NumberSetting("Self Delay Ticks", 2, 1, 5, 1,
            () -> (Boolean) this.predictionEnabled.getValue());

    public final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 180, 0, 720, 5);
    public final NumberSetting rotationDrift = new NumberSetting("Drift", 0.1, 0, 5, 0.1);
    public final NumberSetting rotationJitter = new NumberSetting("Jitter", 0.02, 0, 1, 0.01);

    private RotationUtil.BestHitInfo currentBestHit;
    private RotationUtil.BestHitInfo prevBestHit;
    private int attackTimes;
    private float attacks;
    private int targetIndex;
    public int sprintTickCounter;
    private int sprintCounter;
    public Rotation rotation;

    private Random organicRandom;
    private double organicTimeAccumulator;
    private double orgFreqYaw1, orgFreqYaw2, orgFreqPitch1, orgFreqPitch2;
    private double orgPhaseYaw1, orgPhaseYaw2, orgPhasePitch1, orgPhasePitch2;

    public KillAura() {
        super("KillAura", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.rotation = null;
        this.reinitOrganicModel();
        this.targetIndex = 0;
        this.attacks = 0.0f;
        target = null;
        aimingTarget = null;
        targetList.clear();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.attacks = 0.0f;
        target = null;
        aimingTarget = null;
        this.sprintTickCounter = 0;
        this.sprintCounter = 0;
        this.attackTimes = 0;
        this.rotation = null;
        super.onDisable();
    }

    @Override
    public String getSuffix() {
        return this.style.getValue();
    }

    private void reinitOrganicModel() {
        this.organicRandom = new Random(System.nanoTime());
        this.organicTimeAccumulator = 0.0;
        this.orgFreqYaw1 = this.organicRandom.nextDouble() * 0.3 + 0.1;
        this.orgFreqYaw2 = this.organicRandom.nextDouble() * 0.5 + 0.5;
        this.orgFreqPitch1 = this.organicRandom.nextDouble() * 0.3 + 0.1;
        this.orgFreqPitch2 = this.organicRandom.nextDouble() * 0.5 + 0.5;
        this.orgPhaseYaw1 = this.organicRandom.nextDouble() * Math.PI * 2;
        this.orgPhaseYaw2 = this.organicRandom.nextDouble() * Math.PI * 2;
        this.orgPhasePitch1 = this.organicRandom.nextDouble() * Math.PI * 2;
        this.orgPhasePitch2 = this.organicRandom.nextDouble() * Math.PI * 2;
    }

    private Rotation applyOrganicRotation(Rotation from, Rotation to, float timeDelta) {
        float rawYawDelta = Mth.wrapDegrees(to.getYaw() - from.getYaw());
        float rawPitchDelta = to.getPitch() - from.getPitch();

        double speed = this.rotationSpeed.getValue().doubleValue();
        double driftIntensity = this.rotationDrift.getValue().doubleValue();
        double jitterIntensity = this.rotationJitter.getValue().doubleValue();

        // speed=0: 瞬转，不加漂移抖动
        if (speed <= 0) {
            return to;
        }

        float deltaYaw = rawYawDelta * timeDelta;
        float deltaPitch = rawPitchDelta * timeDelta;

        double distance = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (distance < driftIntensity) {
            return new Rotation(from.getYaw() + deltaYaw, from.getPitch() + deltaPitch);
        }

        if (distance > 0) {
            double ratioYaw = Math.abs(deltaYaw) / distance;
            double ratioPitch = Math.abs(deltaPitch) / distance;
            double maxYaw = speed * ratioYaw * timeDelta;
            double maxPitch = speed * ratioPitch * timeDelta;
            deltaYaw = Mth.clamp(deltaYaw, (float)-maxYaw, (float)maxYaw);
            deltaPitch = Mth.clamp(deltaPitch, (float)-maxPitch, (float)maxPitch);
        }

        this.organicTimeAccumulator += timeDelta;

        double sinYaw = Math.sin(this.organicTimeAccumulator * this.orgFreqYaw1 + this.orgPhaseYaw1)
                + (this.organicRandom.nextDouble() * 0.1 + 0.45) * Math.sin(this.organicTimeAccumulator * this.orgFreqYaw2 + this.orgPhaseYaw2);
        double sinPitch = Math.sin(this.organicTimeAccumulator * this.orgFreqPitch1 + this.orgPhasePitch1)
                + (this.organicRandom.nextDouble() * 0.1 + 0.45) * Math.sin(this.organicTimeAccumulator * this.orgFreqPitch2 + this.orgPhasePitch2);
        double driftYaw = sinYaw * driftIntensity * timeDelta;
        double driftPitch = sinPitch * driftIntensity * timeDelta;

        double jitterYaw = (this.organicRandom.nextDouble() * 2 - 1) * jitterIntensity * timeDelta;
        double jitterPitch = (this.organicRandom.nextDouble() * 2 - 1) * jitterIntensity * timeDelta;

        float moveYaw = deltaYaw + (float)driftYaw + (float)jitterYaw;
        float movePitch = deltaPitch + (float)driftPitch + (float)jitterPitch;

        float finalYaw = from.getYaw() + moveYaw;
        float finalPitch = Mth.clamp(from.getPitch() + movePitch, -90.0f, 90.0f);
        return patchConstantRotation(new Rotation(finalYaw, finalPitch), from);
    }

    /**
     * GCD 对齐：将旋转增量取整到灵敏度步长的整数倍，
     * 防止对静止目标产生微抖。等价于 Candy 的 RotationUtility.patchConstantRotation。
     */
    private static Rotation patchConstantRotation(Rotation rotation, Rotation prevRotation) {
        double sensitivity = mc.options.sensitivity().get().floatValue() * 0.6 + 0.2;
        double multiplier = (sensitivity * sensitivity * sensitivity) * 8.0;
        double divisor = multiplier * 0.15;

        float yawDelta = rotation.getYaw() - prevRotation.getYaw();
        float pitchDelta = rotation.getPitch() - prevRotation.getPitch();
        float yaw = prevRotation.getYaw() + (float)(Math.round(yawDelta / divisor) * divisor);
        float pitch = Mth.clamp(prevRotation.getPitch() + (float)(Math.round(pitchDelta / divisor) * divisor), -90.0f, 90.0f);
        return new Rotation(yaw, pitch);
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        target = null;
        aimingTarget = null;
        this.attacks = 0.0f;
        this.setEnabled(false);
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (this.targetEsp.is("None")) return;
        Entity entity = aimingTarget;
        if (entity == null || mc.gameRenderer == null) return;
        PoseStack poseStack = event.poseStack();
        poseStack.pushPose();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        poseStack.translate(-cameraPos.x(), -cameraPos.y(), -cameraPos.z());

        double dx = entity.getX() - entity.xOld;
        double dy = entity.getY() - entity.yOld;
        double dz = entity.getZ() - entity.zOld;
        Vec3 playerDelta = mc.player.getDeltaMovement();
        Vec3 offset = new Vec3(
                dx + playerDelta.x + 0.005,
                dy + playerDelta.y - 0.002,
                dz + playerDelta.z + 0.005);

        String mode = this.targetEsp.getValue();
        switch (mode) {
            case "Spiral" -> RenderUtil.drawSpiralEffect(poseStack, entity, event.partialTick());
            case "Box" -> {
                int hurtTime = entity instanceof LivingEntity le ? le.hurtTime : 0;
                Color color;
                if (hurtTime == 0) {
                    color = new Color(0, 0, 0, 130);
                } else if (hurtTime >= 9 && hurtTime <= 10) {
                    color = new Color(0, 255, 255, 200);
                } else {
                    color = new Color(255, 0, 0, 200);
                }
                AABB base = EntityUtil.getInterpolatedAABB(entity, event.partialTick()).move(offset);
                AABB padded = new AABB(
                        base.minX - 0.175, base.minY - 0.125, base.minZ - 0.175,
                        base.maxX + 0.175, base.maxY + 0.225, base.maxZ + 0.175);
                RenderUtil.drawFilledColoredBox(padded, poseStack, color, color);
            }
            case "Tab" -> {
                int hurtTime = entity instanceof LivingEntity le ? le.hurtTime : 0;
                Color color;
                if (hurtTime == 0) {
                    color = new Color(0, 0, 0, 130);
                } else if (hurtTime == 3) {
                    color = new Color(255, 255, 255, 200);
                } else {
                    color = new Color(255, 0, 0, 200);
                }
                AABB base = EntityUtil.getInterpolatedAABB(entity, event.partialTick()).move(offset);
                AABB band = new AABB(
                        base.minX, base.minY + entity.getEyeHeight() + 0.11, base.minZ,
                        base.maxX, base.maxY - 0.13, base.maxZ);
                RenderUtil.drawFilledColoredBox(band, poseStack, color, color);
            }
            case "NurikZapen" -> this.renderNurikZapen(poseStack, entity, event.partialTick());
            default -> {
            }
        }
        poseStack.popPose();
    }

    private static void ensureNurikCaptureTexture() {
        if (nurikTextureLoaded || nurikTextureLoadFailed) {
            return;
        }
        try (InputStream inputStream = Assets.open(NURIK_CAPTURE_ASSET)) {
            if (inputStream == null) {
                nurikTextureLoadFailed = true;
                System.out.println("KillAura: NurikZapen texture not found - " + NURIK_CAPTURE_ASSET);
                return;
            }
            mc.getTextureManager().register(NURIK_CAPTURE_TEXTURE, new DynamicTexture(NativeImage.read(inputStream)));
            nurikTextureLoaded = true;
        } catch (IOException exception) {
            nurikTextureLoadFailed = true;
            System.out.println("KillAura: failed to load NurikZapen texture - " + exception.getMessage());
        }
    }

    private void renderNurikZapen(PoseStack poseStack, Entity entity, float partialTick) {
        ensureNurikCaptureTexture();
        if (!nurikTextureLoaded) {
            return;
        }

        double x = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) + entity.getEyeHeight() * 0.5f;
        double z = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        Camera camera = mc.gameRenderer.getMainCamera();

        poseStack.pushPose();
        try {
            poseStack.translate(x, y, z);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - camera.getYRot()));
            poseStack.mulPose(Axis.XP.rotationDegrees(-camera.getXRot()));
            poseStack.mulPose(Axis.ZP.rotationDegrees((float) ((System.currentTimeMillis() / 5.0) % 360.0)));

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, NURIK_CAPTURE_TEXTURE);

            ModuleListHud moduleList = NiloreClient.getInstance().getHudManager().getHudElement(ModuleListHud.class);
            int[] colors = moduleList == null
                    ? new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF}
                    : new int[]{
                            moduleList.getThemeColor(0, 0.0f, 3),
                            moduleList.getThemeColor(1, 0.33f, 3),
                            moduleList.getThemeColor(2, 0.67f, 3),
                            moduleList.getThemeColor(3, 1.0f, 3)
                    };
            float size = 0.75f;
            float[][] corners = {
                    {-size, size, 0.0f, 0.0f},
                    {size, size, 1.0f, 0.0f},
                    {size, -size, 1.0f, 1.0f},
                    {-size, -size, 0.0f, 1.0f}
            };
            Matrix4f matrix = poseStack.last().pose();
            BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int i = 0; i < corners.length; i++) {
                int color = colors[i];
                bufferBuilder.vertex(matrix, corners[i][0], corners[i][1], 0.0f)
                        .uv(corners[i][2], corners[i][3])
                        .color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, 200)
                        .endVertex();
            }
            BufferUploader.drawWithShader(bufferBuilder.end());
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            poseStack.popPose();
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!NiloreClient.isReady()) {
            return;
        }
        if (mc.screen instanceof AbstractContainerScreen
                || ItemUtil.hasServerItem()
                || (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled() && !this.style.is("onTickRot"))
                || (Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled())
                || (Helper.INSTANCE != null && Helper.INSTANCE.isEnabled() && Helper.targetRotation != null)
                || AntiWeb.targetRotation != null
                || AntiTNT.targetRotation != null
                || MidPearl.targetRotation != null
                || this.isWebPlacing()) {
            target = null;
            aimingTarget = null;
            this.currentBestHit = null;
            this.rotation = null;
            this.prevBestHit = null;
            targetList.clear();
            this.sprintTickCounter = 0;
            this.attacks = 0.0f;
            this.sprintCounter = 0;
            return;
        }

        boolean isSwitch = this.switchSize.getValue().intValue() > 1
                || this.infSwitch.getValue()
                || this.multiAttack.getValue();
        this.updateTargets();
        aimingTarget = this.getTarget();
        this.prevBestHit = this.currentBestHit;
        this.currentBestHit = null;
        if (aimingTarget != null) {
            this.currentBestHit = RotationUtil.getBestHit(aimingTarget);
            if (this.currentBestHit != null && this.currentBestHit.rotation() != null) {
                if (this.style.is("onTickRot")) {
                    this.rotation = null;
                } else {
                    Rotation from = RotationHandler.prevRotation != null
                            ? RotationHandler.prevRotation
                            : new Rotation(mc.player.getYRot(), mc.player.getXRot());
                    Rotation organic = this.applyOrganicRotation(from, this.currentBestHit.rotation(), 1.0f);
                    this.rotation = (organic != null
                            && !Float.isNaN(organic.getYaw())
                            && !Float.isNaN(organic.getPitch())
                            && !Float.isInfinite(organic.getYaw())
                            && !Float.isInfinite(organic.getPitch()))
                            ? organic
                            : this.currentBestHit.rotation();
                }
            } else {
                this.rotation = null;
            }
        } else {
            this.rotation = null;
        }
        if (targetList.isEmpty()) {
            target = null;
            return;
        }
        if (this.targetIndex > targetList.size() - 1) {
            this.targetIndex = 0;
        }
        if (targetList.size() > 1
                && (this.attackTimes >= this.switchDelay.getValue().intValue()
                || (this.currentBestHit != null && this.currentBestHit.distance() > 3.0))) {
            this.attackTimes = 0;
            for (int i = 0; i < targetList.size(); ++i) {
                ++this.targetIndex;
                if (this.targetIndex > targetList.size() - 1) {
                    this.targetIndex = 0;
                }
                Entity nextTarget = targetList.get(this.targetIndex);
                RotationUtil.BestHitInfo nextHit = RotationUtil.getBestHit(nextTarget);
                if (nextHit != null && nextHit.distance() < 3.0) {
                    break;
                }
            }
        }
        if (this.targetIndex > targetList.size() - 1 || !isSwitch) {
            this.targetIndex = 0;
        }
        target = targetList.get(this.targetIndex);
        if (this.style.is("Old")) {
            float apsValue;
            float minApsValue;
            if (NoXZMode.isAttacking) {
                int kbAttackAmount = AntiKB.INSTANCE != null
                        ? AntiKB.INSTANCE.attackAmount.getValue().intValue()
                        : 0;
                apsValue = this.maxAps.getValue().floatValue() - kbAttackAmount;
                minApsValue = this.minAps.getValue().floatValue() - kbAttackAmount;
            } else {
                apsValue = this.maxAps.getValue().floatValue();
                minApsValue = this.minAps.getValue().floatValue();
            }
            this.attacks += (float)(MathUtil.randomDouble(minApsValue, apsValue) / 20.0);
        } else {
            float apsValue = this.maxAps.getValue().floatValue();
            float minApsValue = this.minAps.getValue().floatValue();
            if (NoXZMode.isAttacking) {
                int kbAttackAmount = AntiKB.INSTANCE != null
                        ? AntiKB.INSTANCE.attackAmount.getValue().intValue()
                        : 0;
                apsValue -= kbAttackAmount;
                minApsValue -= kbAttackAmount;
            }
            this.attacks += (float)(MathUtil.randomDouble(minApsValue, apsValue) / 20.0);
        }
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        if (mc.player == null) return;
        if (this.isWebPlacing()) {
            this.attacks = 0.0f;
            return;
        }
        if (mc.player.getUseItem().isEmpty()
                && mc.screen == null
                && (this.ignoreSkipTicks.getValue() || ClientBase.delayPackets.isEmpty()
                || (Critical.INSTANCE != null && Critical.INSTANCE.isEnabled()))) {
            while (this.attacks >= 1.0f) {
                if (this.style.is("Old") && (Boolean) this.fix.getValue()) {
                    if (!this.doAttack()) {
                        break;
                    }
                } else {
                    this.doAttack();
                }
                this.attacks -= 1.0f;
            }
        } else {
            this.attacks = 0.0f;
        }
    }

    public boolean doAttack() {
        if (this.isWebPlacing()) {
            this.attacks = 0.0f;
            return false;
        }
        if (targetList.isEmpty()) return false;
        if (this.rotation == null && !this.style.is("onTickRot")) return false;

        HitResult hitResult;
        if ((this.style.is("Old") || this.style.is("onTickRot"))
                && (Boolean) this.overrideRaycast.getValue()
                && this.currentBestHit != null && this.currentBestHit.rotation() != null) {
            hitResult = RotationUtil.performRaycast(this.currentBestHit.rotation());
            if (hitResult == null || hitResult.getType() != HitResult.Type.ENTITY) {
                return false;
            }
        } else {
            hitResult = mc.hitResult;
        }
        if (hitResult != null && hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) hitResult).getEntity();
            if (AntiBots.isBot(hitEntity)) {
                ChatUtil.print("Skipped attack on suspected bot");
                return false;
            }
        }
        if (this.multiAttack.getValue()) {
            int attacked = 0;
            Rotation aimRot = this.currentBestHit != null && this.currentBestHit.rotation() != null
                    ? this.currentBestHit.rotation()
                    : RotationHandler.targetRotation;
            if (aimRot == null) {
                aimRot = new Rotation(mc.player.getYRot(), mc.player.getXRot());
            }
            for (Entity entity : targetList) {
                if (mc.player == null) break;
                if (RotationUtil.getHitDistance(entity, mc.player.getEyePosition(), aimRot) >= 3.0) continue;
                if (this.attackEntity(entity)) {
                    attacked++;
                }
                if (attacked >= 2) break;
            }
            return attacked > 0;
        } else if (hitResult != null && hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) hitResult).getEntity();
            return this.attackEntity(hitEntity);
        } else if (target != null && targetList.contains(target)) {
            return this.attackEntity(target);
        }
        return false;
    }

    public Entity getTarget() {
        Entity entity = target;
        if (entity == null) {
            List<Entity> list = this.getTargets();
            if (!list.isEmpty()) {
                entity = list.get(0);
            }
        }
        if (entity != null) {
            AntiBots antiBots = AntiBots.INSTANCE;
            if (antiBots != null && antiBots.isEnabled() && AntiBots.isBot(entity)) {
                return null;
            }
        }
        return entity;
    }

    public void updateTargets() {
        List<Entity> next = this.getTargets();
        targetList = next != null ? next : new ArrayList<>();
    }

    public boolean isValidTarget(Entity entity) {
        if (!NiloreClient.isReady()) return false;
        if (entity == mc.player) return false;
        if (entity instanceof LivingEntity livingEntity) {
            AntiBots antiBots = AntiBots.INSTANCE;
            if (antiBots != null && antiBots.isEnabled() && (AntiBots.isBot(entity) || AntiBots.isBedWarsBot(entity))) {
                return false;
            }
            if (livingEntity.isDeadOrDying() || livingEntity.getHealth() <= 0.0f) return false;
            if (entity instanceof ArmorStand) return false;
            if (entity.isInvisible() && !(Boolean) this.attackInvisible.getValue()) return false;
            if (entity instanceof Player player) {
                if (this.test.getValue() && player.getY() >= mc.player.getY() + 0.05f) {
                    return true;
                }
                // NiloreClient.isOwner() was stripped during deobfuscation; the
                // original jar bailed here when the entity name matched the
                // client owner. Re-enable once that helper is restored.
            }
            if (Teams.isSameTeam(entity)) return false;
            if (entity instanceof Player && !(Boolean) this.attackPlayer.getValue()) return false;
            if (entity instanceof Player && (entity.getBbWidth() < 0.5 || livingEntity.isSleeping())) return false;
            if ((entity instanceof Mob || entity instanceof Slime || entity instanceof Bat || entity instanceof AbstractGolem)
                    && !(Boolean) this.attackMobs.getValue()) {
                return false;
            }
            if ((entity instanceof Animal || entity instanceof Squid) && !(Boolean) this.attackAnimals.getValue()) {
                return false;
            }
            if (entity instanceof Villager && !(Boolean) this.attackAnimals.getValue()) return false;
            return !(entity instanceof Player) || !entity.isSpectator();
        }
        return false;
    }

    public boolean isValidAttack(Entity entity) {
        if (mc.player == null) return false;
        if (!this.isValidTarget(entity)) return false;
        if (entity instanceof LivingEntity le && le.hurtTime > this.hurtTime.getValue().intValue()) {
            return false;
        }
        Vec3 vec3 = RotationUtil.closestPoint(mc.player.getEyePosition(), entity.getBoundingBox());
        double dist = vec3.distanceTo(mc.player.getEyePosition());
        float aimRange = this.aimRange.getValue().floatValue();
        if (dist <= aimRange) {
        } else if (dist > 5.0) {
            return false;
        } else {
            if (!(Boolean) this.predictionEnabled.getValue()
                    || this.predictDistance(entity) >= aimRange) {
                return false;
            }
        }
        // Wall check (New / onTickRot only) — reject if a block sits between
        // the player and the entity. When "Through Walls" is on and the
        // target is close enough, the check is skipped so you can attack
        // through thin walls at close range.
        if ((this.style.is("New") || this.style.is("onTickRot")) && mc.level != null) {
            boolean skipWallCheck = this.throughWalls.getValue()
                    && dist <= this.throughWallsRange.getValue().floatValue();
            if (!skipWallCheck) {
                Vec3 eyePos = mc.player.getEyePosition(1.0f);
                Vec3 targetPoint = RotationUtil.closestPoint(eyePos, entity.getBoundingBox());
                if (eyePos.distanceToSqr(targetPoint) > 1.0E-4) {
                    BlockHitResult blockHit = mc.level.clip(new ClipContext(eyePos, targetPoint, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                    if (blockHit.getType() == HitResult.Type.BLOCK) {
                        return false;
                    }
                }
            }
        }
        return RotationUtil.isEntityInFov(entity, this.fov.getValue().floatValue() / 2.0f);
    }

    private double predictDistance(Entity entity) {
        double selfDelayMs = 0.0;
        if (mc.getConnection() != null
                && mc.getConnection().getPlayerInfo(mc.player.getUUID()) != null) {
            selfDelayMs = mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency();
        }
        double selfDelayTicks = Math.min(selfDelayMs / 50.0, this.selfDelayThreshold.getValue().doubleValue());

        double enemyDelayMs = 0.0;
        if (entity instanceof Player player) {
            if (mc.getConnection() != null
                    && mc.getConnection().getPlayerInfo(player.getUUID()) != null) {
                enemyDelayMs = mc.getConnection().getPlayerInfo(player.getUUID()).getLatency();
            }
        }
        double enemyDelayTicks = Math.min(enemyDelayMs / 50.0, this.enemyDelayThreshold.getValue().doubleValue());

        double totalTicks = 2.0 + selfDelayTicks + enemyDelayTicks;

        double playerVelX = mc.player.getX() - mc.player.xOld;
        double playerVelZ = mc.player.getZ() - mc.player.zOld;
        double enemyVelX = entity.getX() - entity.xOld;
        double enemyVelZ = entity.getZ() - entity.zOld;

        double predictedPlayerX = mc.player.getX() + playerVelX * totalTicks;
        double predictedPlayerZ = mc.player.getZ() + playerVelZ * totalTicks;
        double predictedEnemyX = entity.getX() + enemyVelX * totalTicks;
        double predictedEnemyZ = entity.getZ() + enemyVelZ * totalTicks;

        double dx = predictedEnemyX - predictedPlayerX;
        double dz = predictedEnemyZ - predictedPlayerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public boolean attackEntity(Entity entity) {
        if (mc.player == null || mc.gameMode == null) return false;
        if (this.isWebPlacing()) return false;

        if (this.style.is("onTickRot")) {
            return this.attackEntityNewFix(entity);
        }

        // KeepSprint(参考 res): 攻击前记录疾跑状态, 疾跑攻击时保持疾跑
        boolean keepSprinting = this.keepSprint.getValue() && mc.player.isSprinting();

        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        if (RotationHandler.targetRotation != null) {
            mc.player.setYRot(RotationHandler.targetRotation.getYaw());
            mc.player.setXRot(RotationHandler.targetRotation.getPitch());
        }

        ++this.attackTimes;
        int attackKey = mc.options.keyAttack.getKey().getValue();
        mc.gameMode.attack(mc.player, entity);
        ForgeHooksClient.onMouseButtonPre(attackKey, 1, 0);
        mc.player.swing(InteractionHand.MAIN_HAND);
        ForgeHooksClient.onMouseButtonPost(attackKey, 1, 0);

        if (this.morePart.getValue()) {
            mc.player.magicCrit(entity);
            mc.player.crit(entity);
        }

        // KeepSprint: 攻击后保持疾跑; Critical 松疾跑窗口(hurtTime∈[2,8])内不恢复
        if (keepSprinting && !mc.player.isSprinting() && !this.criticalReleaseWindow()) {
            mc.player.setSprinting(true);
        }

        mc.player.setYRot(currentYaw);
        mc.player.setXRot(currentPitch);

        if (this.delayMode.is("1.9")) {
            this.sprintCounter = (int) mc.player.getCurrentItemAttackStrengthDelay();
        }
        return true;
    }

    private boolean attackEntityNewFix(Entity entity) {
        if (mc.getConnection() == null) return false;

        // KeepSprint(参考 res): 攻击前记录疾跑状态
        boolean keepSprinting = this.keepSprint.getValue() && mc.player.isSprinting();

        float origYaw = mc.player.getYRot();
        float origPitch = mc.player.getXRot();

        Rotation targetRot = this.currentBestHit != null ? this.currentBestHit.rotation() : null;
        if (targetRot == null) return false;

        float jitter1 = rotationJitter();
        float jitter2 = rotationJitter();
        float jitter3 = rotationJitter();

        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                targetRot.getYaw() + jitter1,
                targetRot.getPitch() + jitter1,
                mc.player.onGround()
        ));

        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                targetRot.getYaw() + jitter2,
                targetRot.getPitch() + jitter2,
                mc.player.onGround()
        ));

        ++this.attackTimes;
        mc.gameMode.attack(mc.player, entity);
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (this.morePart.getValue()) {
            mc.player.magicCrit(entity);
            mc.player.crit(entity);
        }

        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                origYaw + jitter3,
                origPitch + jitter3,
                mc.player.onGround()
        ));

        // KeepSprint: 攻击后保持疾跑; Critical 松疾跑窗口(hurtTime∈[2,8])内不恢复
        if (keepSprinting && !mc.player.isSprinting() && !this.criticalReleaseWindow()) {
            mc.player.setSprinting(true);
        }

        return true;
    }

    // Critical 松疾跑窗口(hurtTime∈[2,8])内, 攻击后不主动恢复疾跑, 避免 KeepSprint 与 Critical 互相拉扯
    private boolean criticalReleaseWindow() {
        return Critical.INSTANCE != null && Critical.INSTANCE.isEnabled()
                && Critical.INSTANCE.isReleaseWindow();
    }

    private float rotationJitter() {
        float offset = (float) (Math.random() * 0.04 + 0.03);
        return Math.random() > 0.5 ? offset : -offset;
    }

    private boolean isWebPlacing() {
        return AutoWebPlace.INSTANCE != null && AutoWebPlace.INSTANCE.isEnabled() && AutoWebPlace.targetRotation != null;
    }

    private List<Entity> getTargets() {
        if (mc.player == null || mc.level == null) {
            return new ArrayList<>();
        }
        Stream<Entity> stream = StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), true)
                .filter(this::isValidAttack);
        List<Entity> possibleTargets = stream.collect(Collectors.toList());
        if (this.priorityMode.is("Distance")) {
            possibleTargets.sort(Comparator.comparingDouble(KillAura::getDistanceToPlayer));
        } else if (this.priorityMode.is("FoV")) {
            possibleTargets.sort(Comparator.comparingDouble(KillAura::getAngleDiffToTarget));
        } else if (this.priorityMode.is("Health")) {
            possibleTargets.sort(Comparator.comparingDouble(KillAura::getEntityHealth));
        }
        if (this.preferBaby.getValue()
                && possibleTargets.stream().anyMatch(KillAura::isBaby)) {
            possibleTargets.removeIf(KillAura::isNotBaby);
        }
        possibleTargets.sort(Comparator.comparing(KillAura::getCrystalPriority));
        if (this.infSwitch.getValue()) {
            return possibleTargets;
        }
        int limit = (int) Math.min(possibleTargets.size(), this.switchSize.getValue().intValue());
        return new ArrayList<>(possibleTargets.subList(0, limit));
    }

    private static Integer getCrystalPriority(Entity entity) {
        return entity instanceof EndCrystal ? 0 : 1;
    }

    private static boolean isNotBaby(Entity entity) {
        return !(entity instanceof LivingEntity) || !((LivingEntity) entity).isBaby();
    }

    private static boolean isBaby(Entity entity) {
        return entity instanceof LivingEntity && ((LivingEntity) entity).isBaby();
    }

    private static double getEntityHealth(Entity entity) {
        if (entity instanceof LivingEntity le) {
            return le.getHealth();
        }
        return 0.0;
    }

    private static double getAngleDiffToTarget(Entity entity) {
        float baseYaw = RotationHandler.targetRotation != null
                ? RotationHandler.targetRotation.getYaw()
                : mc.player.getYRot();
        return RotationUtil.angleDiff(baseYaw, RotationUtil.entityRotation(entity).getYaw());
    }

    private static double getDistanceToPlayer(Entity entity) {
        return entity.distanceTo(mc.player);
    }

    private static boolean isLivingEntity(Entity entity) {
        return entity instanceof LivingEntity;
    }
}
