package client.nilore.modules.impl.combat;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.antikb.NoXZMode;

/**
 * Critical(极简版, 无配置): 每 tick 从 KillAura 取目标(target), 当目标处于受击硬直窗口
 * hurtTime∈[2,8](命中结算后的受击段)时松疾跑; 疾跑重启交给 KeepSprint/移动 Sprint 模块。
 * KillAura 的 KeepSprint 通过 {@link #isReleaseWindow()} 兼容: 窗口内不主动恢复疾跑。
 */
public class Critical extends Module {
    public static Critical INSTANCE;

    public Critical() {
        super("Critical", Category.COMBAT);
        INSTANCE = this;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null) {
            return;
        }
        if (this.isReleaseWindow()) {
            mc.options.keySprint.setDown(false);
            if (mc.player.isSprinting()) {
                mc.player.setSprinting(false);
            }
        }
    }

    /**
     * 松疾跑窗口: KillAura 目标存在且 LivingEntity.hurtTime ∈ [2,8]。
     * 供 KillAura KeepSprint 在攻击后决定是否恢复疾跑(窗口内不恢复)。
     */
    public boolean isReleaseWindow() {
        // res 对齐: 击退收放(Velocity/NoXZ Alink)进行中 Critical 停手, 避免松疾跑打断放包
        if (NoXZMode.handlingVelocity) return false;
        if (mc.player == null) return false;
        Entity target = KillAura.target;
        if (!(target instanceof LivingEntity living)) {
            return false;
        }
        // —— 玩家自身处于不可触发 crit 状态, 逐一显式 return false ——
        if (mc.player.onGround()) return false;
        if (mc.player.isInWater() || mc.player.isInLava()) return false;  // 原 isInLava()&&isInWater() 互斥恒 false, 改 || 表达"任一液体"
        if (mc.player.isUsingItem()) return false;
        if (mc.player.isShiftKeyDown()) return false;
        if (mc.player.isFallFlying()) return false;
        if (mc.player.isPassenger()) return false;
        if (mc.player.onClimbable()) return false;
        if (mc.player.hasEffect(MobEffects.BLINDNESS)) return false;
        if (mc.player.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) return false;
        if (mc.player.hasEffect(MobEffects.LEVITATION)) return false;
        int hurtTime = living.hurtTime;
        return hurtTime >= 7 || hurtTime <= 3;
    }
}
