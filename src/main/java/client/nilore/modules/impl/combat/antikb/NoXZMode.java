package client.nilore.modules.impl.combat.antikb;

import java.awt.Color;
import java.util.concurrent.LinkedBlockingDeque;

import client.nilore.NiloreClient;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.event.impl.RotationEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.impl.combat.AntiKB;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.utils.render.RenderUtil;

public class NoXZMode
        extends AntiKBMode {
    public static NoXZMode INSTANCE;
    public static boolean isAttacking;
    public static boolean handlingVelocity;
    public static boolean velocityHandled;
    public static int attackCount;
    private int attackCooldown = 0;
    private Entity attackTarget = null;
    private int attacksRemaining = 0;
    private int flagCooldown = 0;
    private boolean shouldJump = false;
    private int sprintBoostCounter = 0;
    private int hitCounter = 0;
    private boolean isSuspending = false;
    private int delayTicks = 0;
    private ClientboundSetEntityMotionPacket knockbackPacket = null;
    private final LinkedBlockingDeque<Packet<ClientGamePacketListener>> packetQueue = new LinkedBlockingDeque();
    private float instantAttackProgress = 0.0f;
    private boolean isInstantAttacking = false;

    @Override
    public boolean isActive() {
        return this.velocityHandled;
    }

    public NoXZMode() {
        super("NoXZ");
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.resetAll();
    }

    @Override
    public void onDisable() {
        this.resetAll();
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public void onRotation(RotationEvent rotationEvent) {
    }

    @Override
    public void onMotion(MotionEvent motionEvent) {
    }

    @Override
    public void onGameTick(GameTickEvent gameTickEvent) {
    }

    @Override
    public void onPreMotion(PreMotionEvent preMotionEvent) {
    }

    @Override
    public void onSprint(SprintEvent sprintEvent) {
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent receivePacketEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Packet<ClientGamePacketListener> packet = receivePacketEvent.getPacket();
        if (packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket) {
            this.resetAll();
            return;
        }
        if (packet instanceof ClientboundPlayerPositionPacket) {
            if (this.isSuspending) {
                this.release();
            }
            this.resetSuspension();
            if (AntiKB.INSTANCE.debugLog.getValue()) {
                ChatUtil.print("Flag Detected");
            }
            this.flagCooldown = 2;
            return;
        }
        if (this.flagCooldown != 0) {
            return;
        }
        if (this.isSuspending) {
            // Alink 收放包: 暂缓服务器→客户端包, 放行自己的 Move 包
            if (packet instanceof ClientboundMoveEntityPacket move && move.getEntity(mc.level) == mc.player) {
                return;
            }
            if (packet instanceof ClientboundMoveEntityPacket
                    || packet instanceof ClientboundPingPacket
                    || packet instanceof ClientboundTeleportEntityPacket) {
                this.packetQueue.add(packet);
                receivePacketEvent.setCancelled(true);
            } else if (!this.isAllowedPacket(packet)) {
                this.packetQueue.add(packet);
                receivePacketEvent.setCancelled(true);
            }
            return;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motionPacket) {
            if (motionPacket.getId() != mc.player.getId()) {
                return;
            }
            if (!this.canProcess()) {
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("Alink Wait");
                }
                this.resetAll();
                return;
            }
            double dx = -motionPacket.getXa();
            double dz = -motionPacket.getZa();
            if (Math.abs(dx) > 0.01 || Math.abs(dz) > 0.01) {
                this.hitCounter = 1;
            }
            if (motionPacket.getYa() > 0) {
                Entity target;
                this.sprintBoostCounter = this.sprintBoostCounter % 100 + 100;
                if (this.sprintBoostCounter >= 100) {
                    this.shouldJump = true;
                }
                // res 对齐: 收击退包瞬间不 gate 疾跑(res VelocityModule 收包一律暂缓), 疾跑只在落地放行当闸
                boolean canAttack = this.isValidTarget(target = this.getAttackTarget());
                if (!mc.player.onGround()) {
                    this.enterSuspension(motionPacket);
                    receivePacketEvent.setCancelled(true);
                } else if (canAttack) {
                    this.attackTarget = target;
                    this.attacksRemaining = this.getAttackCount(motionPacket);
                } else {
                    this.enterSuspension(motionPacket);
                    receivePacketEvent.setCancelled(true);
                    if (AntiKB.INSTANCE.debugLog.getValue()) {
                        ChatUtil.print("Alink Wait");
                    }
                }
            }
        }
    }

    @Override
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        this.resetAll();
    }

    @Override
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.attackCooldown > 0) {
            --this.attackCooldown;
            if (this.attackCooldown <= 0) {
                isAttacking = false;
                attackCount = 0;
                velocityHandled = false;
            }
        }
        if (this.hitCounter > 0) {
            ++this.hitCounter;
            if (this.hitCounter > 2) {
                this.hitCounter = 0;
            }
        }
        if (mc.player.isDeadOrDying() || !mc.player.isAlive() || this.shouldIgnore()) {
            this.clearTarget();
            if (this.isSuspending) {
                this.release();
            }
            if (this.isInstantAttacking) {
                this.isInstantAttacking = false;
                this.instantAttackProgress = 0.0f;
                NiloreClient.serverTickRate = 1.0f;
            }
            return;
        }
        if (this.flagCooldown > 0) {
            --this.flagCooldown;
            this.clearTarget();
        }
        if (this.isSuspending) {
            ++this.delayTicks;
            // Alink 超时: 暂缓太久直接放弃, 放行全部暂缓包并重置
            if (this.delayTicks >= AntiKB.INSTANCE.maxDelayTicks.getValue().intValue()) {
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("Alink Timeout");
                }
                this.resetAll();
                return;
            }
            boolean instantAttackEnabled = AntiKB.INSTANCE.instantAttack.getValue();
            if (instantAttackEnabled && this.instantAttackProgress < 3.0f) {
                float tickRate;
                NiloreClient.serverTickRate = tickRate = 0.5f;
                this.instantAttackProgress += 1.0f - tickRate;
                this.instantAttackProgress = Math.min(this.instantAttackProgress, 3.0f);
            }
            if (mc.player.onGround()) {
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("ground");
                }
                if (instantAttackEnabled) {
                    NiloreClient.serverTickRate = 1.0f;
                }
                Entity target = this.getAttackTarget();
                boolean canAttack = this.isValidTarget(target);
                boolean sprinting = mc.player.isSprinting();
                if (canAttack && sprinting) {
                    // 放: 异步放行暂缓的服务器→客户端包(含击退包)
                    this.flushQueue();
                    this.attackTarget = target;
                    this.attacksRemaining = this.getAttackCount(this.knockbackPacket);
                    if (instantAttackEnabled && this.instantAttackProgress > 0.0f) {
                        this.attacksRemaining = (int)this.instantAttackProgress;
                        this.isSuspending = false;
                        handlingVelocity = false;
                        this.delayTicks = 0;
                        this.isInstantAttacking = true;
                        NiloreClient.serverTickRate = 4.0f;
                    } else {
                        this.doAttackSequence(tickEvent);
                        this.isSuspending = false;
                        handlingVelocity = false;
                        this.delayTicks = 0;
                    }
                } else {
                    this.release();
                    if (instantAttackEnabled) {
                        this.instantAttackProgress = 0.0f;
                    }
                    // res 对齐: 落地不满足时不主动 setSprinting(false), 疾跑交给移动模块, 避免与 Critical 松疾跑互相拉扯
                }
                return;
            }
            return;
        }
        if (this.isInstantAttacking) {
            this.instantAttackProgress -= 1.0f;
            if (this.instantAttackProgress <= 0.0f) {
                this.instantAttackProgress = 0.0f;
                this.isInstantAttacking = false;
                NiloreClient.serverTickRate = 1.0f;
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("done");
                }
            }
        }
        if (this.attacksRemaining > 0 && this.attackTarget != null) {
            this.doAttackSequence(tickEvent);
        }
    }

    @Override
    public void onStrafe(StrafeEvent strafeEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.hitCounter > 0) {
            strafeEvent.setForward(1.0f);
        }
        if (this.shouldJump) {
            this.shouldJump = false;
            if (mc.player.onGround() && mc.player.isSprinting() && !mc.player.hasEffect(MobEffects.JUMP) && !this.shouldIgnore()) {
                strafeEvent.setSprinting(true);
            }
        }
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (!AntiKB.INSTANCE.renderBar.getValue()
                || !AntiKB.INSTANCE.isEnabled()
                || (!handlingVelocity && !velocityHandled)) {
            return;
        }
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        float barWidth = 100.0f;
        float barHeight = 2.0f;
        float barX = width / 2.0f - barWidth / 2.0f;
        float barY = height / 2.0f + height * 0.10f;

        // 灰黑色背景(整条)
        RenderUtil.drawFilledRect(event.poseStack(), barX, barY, barWidth, barHeight,
                new Color(30, 30, 36, 180).getRGB());
        // 青蓝色进度
        float progress = Math.min(1.0f,
                (float) this.delayTicks / Math.max(1, AntiKB.INSTANCE.maxDelayTicks.getValue().intValue()));
        if (progress > 0.0f) {
            RenderUtil.drawFilledRect(event.poseStack(), barX, barY, barWidth * progress, barHeight,
                    new Color(0, 180, 255, 230).getRGB());
        }
    }

    private void enterSuspension(ClientboundSetEntityMotionPacket packet) {
        this.isSuspending = true;
        handlingVelocity = true;
        velocityHandled = true;
        this.delayTicks = 0;
        this.knockbackPacket = packet;
        this.packetQueue.add(packet);
    }

    private boolean canProcess() {
        return !AntiKB.INSTANCE.requireKillAura.getValue()
                || (KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled());
    }

    private void resetAll() {
        this.flushQueue();
        this.clearTarget();
        this.flagCooldown = 0;
        this.shouldJump = false;
        this.sprintBoostCounter = 0;
        this.hitCounter = 0;
        this.resetSuspension();
    }

    private void clearTarget() {
        this.attackTarget = null;
        this.attacksRemaining = 0;
    }

    private void resetSuspension() {
        this.isSuspending = false;
        handlingVelocity = false;
        velocityHandled = false;
        this.delayTicks = 0;
        this.knockbackPacket = null;
        this.instantAttackProgress = 0.0f;
        this.isInstantAttacking = false;
        NiloreClient.serverTickRate = 1.0f;
    }

    private void release() {
        this.flushQueue();
        this.resetSuspension();
    }

    private boolean shouldIgnore() {
        if (mc.player == null || mc.level == null) {
            return true;
        }
        if (mc.player.isDeadOrDying() || !mc.player.isAlive() || mc.player.getHealth() <= 0.0f) {
            return true;
        }
        if (mc.player.isSpectator() || mc.player.getAbilities().flying) {
            return true;
        }
        if (mc.player.isInLava() || mc.player.isOnFire() || mc.player.isInWater() || mc.player.onClimbable() || mc.player.isSleeping()) {
            return true;
        }
        if (mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.COBWEB)) {
            return true;
        }
        Stuck stuck = Stuck.INSTANCE;
        return stuck != null && stuck.isEnabled();
    }

    private int getAttackCount(ClientboundSetEntityMotionPacket motionPacket) {
        if (!AntiKB.INSTANCE.autoAttackCount.getValue() || motionPacket == null) {
            return AntiKB.INSTANCE.attackAmount.getValue().intValue();
        }
        double velocity = Math.sqrt((double) motionPacket.getXa() * motionPacket.getXa()
                + (double) motionPacket.getYa() * motionPacket.getYa());
        if (velocity < 1000.0) {
            return 0;
        }
        if (velocity < 2000.0) {
            return 3;
        }
        if (velocity < 10000.0) {
            return 4;
        }
        return 5;
    }

    private double getAABBDistance(Entity entity) {
        if (mc.player == null) {
            return Double.MAX_VALUE;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        AABB box = entity.getBoundingBox();
        double clampedX = Math.max(box.minX, Math.min(eyePos.x, box.maxX));
        double clampedY = Math.max(box.minY, Math.min(eyePos.y, box.maxY));
        double clampedZ = Math.max(box.minZ, Math.min(eyePos.z, box.maxZ));
        return eyePos.distanceTo(new Vec3(clampedX, clampedY, clampedZ));
    }

    private Entity getHitResultEntity() {
        Entity hitEntity;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY && (hitEntity = ((EntityHitResult)mc.hitResult).getEntity()) instanceof LivingEntity && hitEntity != mc.player && hitEntity.isAlive() && !hitEntity.isSpectator()) {
            return hitEntity;
        }
        return null;
    }

    private Entity getAttackTarget() {
        if (KillAura.target != null) {
            return KillAura.target;
        }
        return this.getHitResultEntity();
    }

    private boolean isValidTarget(Entity entity) {
        LivingEntity livingEntity;
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        if (entity instanceof LivingEntity && ((livingEntity = (LivingEntity)entity).isDeadOrDying() || livingEntity.getHealth() <= 0.0f)) {
            return false;
        }
        double maxReach = 3.7f;
        return !(this.getAABBDistance(entity) > maxReach);
    }

    private void doAttackSequence(TickEvent tickEvent) {
        if (this.attackTarget == null || !this.attackTarget.isAlive()) {
            this.clearTarget();
            return;
        }
        double maxReach = 3.7f;
        if (this.getAABBDistance(this.attackTarget) > maxReach) {
            this.clearTarget();
            return;
        }
        isAttacking = true;
        attackCount = this.attacksRemaining--;
        this.attackCooldown = 2;
        this.doAttack(this.attackTarget);
        if (this.attacksRemaining <= 0) {
            this.clearTarget();
            if (AntiKB.INSTANCE.instantAttack.getValue()) {
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("Attack (" + AntiKB.INSTANCE.attackAmount.getValue().intValue() + ")");
                }
            }
        }
    }

    private boolean doAttack(Entity entity) {
        if (mc.player == null || mc.gameMode == null) {
            return false;
        }
        if (AntiKB.INSTANCE.sprintStateCheck.getValue() && !mc.player.isSprinting()) {
            if (AntiKB.INSTANCE.debugLog.getValue()) {
                ChatUtil.print("not sprinting");
            }
            return false;
        }
        boolean wasSprinting = mc.player.isSprinting();
        if (wasSprinting) {
            mc.player.setSprinting(false);
        }
        mc.gameMode.attack(mc.player, entity);
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (wasSprinting) {
            Vec3 velocity = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(velocity.x * 0.6, velocity.y, velocity.z * 0.6);
        }
        if (!AntiKB.INSTANCE.instantAttack.getValue()) {
            if (AntiKB.INSTANCE.debugLog.getValue()) {
                ChatUtil.print("Attack (" + this.attacksRemaining + ")");
            }
        }
        return true;
    }

    private void flushQueue() {
        if (mc.getConnection() == null) {
            this.packetQueue.clear();
            return;
        }
        mc.execute(() -> {
            Packet<ClientGamePacketListener> packet;
            while ((packet = this.packetQueue.poll()) != null) {
                try {
                    packet.handle(mc.getConnection());
                } catch (Exception exception) {
                    this.packetQueue.clear();
                    break;
                }
            }
        });
    }

    private boolean isAllowedPacket(Packet<?> packet) {
        return packet instanceof ClientboundSetEntityMotionPacket || packet instanceof ClientboundSetHealthPacket || packet instanceof ClientboundPlayerPositionPacket || packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundLoginPacket || packet instanceof ClientboundSoundPacket || packet instanceof ClientboundPlayerChatPacket || packet instanceof ClientboundPlayerCombatKillPacket || packet instanceof ClientboundContainerClosePacket || packet instanceof ClientboundHurtAnimationPacket || packet instanceof ClientboundSetTitleTextPacket || packet instanceof ClientboundSetPlayerTeamPacket || packet instanceof ClientboundSystemChatPacket || packet instanceof ClientboundDisconnectPacket || packet instanceof ClientboundAnimatePacket && ((ClientboundAnimatePacket)packet).getId() != mc.player.getId();
    }

    static {
        isAttacking = false;
        handlingVelocity = false;
        velocityHandled = false;
        attackCount = 0;
    }
}