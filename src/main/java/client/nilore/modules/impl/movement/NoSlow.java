package client.nilore.modules.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.SlowdownEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.event.EventTarget;

public class NoSlow extends Module {
    public static NoSlow INSTANCE;
    public static boolean releaseItemSent;

    private enum Step { NONE, ARMED, EATING }

    private final Queue<Packet<ClientGamePacketListener>> inboundQueue = new ArrayDeque<>();
    private InteractionHand useHand     = InteractionHand.MAIN_HAND;
    private InteractionHand lastUseHand = InteractionHand.MAIN_HAND;
    private InteractionHand pendingUseHand;
    private boolean didSwapHand;
    private boolean shouldReleaseItem;
    private int swapInitSlot;
    private int releaseTicksRemaining;
    private int pendingUseCount;
    private boolean isBlinking;
    private int blinkTicks;
    private int blinkDuration;
    private Step step = Step.NONE;
    private boolean hasSwapped = false;
    private boolean swapInArmed = false;
    private int noUseTicks = 0;
    private boolean bowActive;   // res apheһһ: 弓类使用中, onSlowdown 取消减速
    private boolean bowDelay;    // res xhc: 弓类使用期间延迟入站包
    private final Queue<Packet<?>> cached = new ConcurrentLinkedQueue<>();

    public NoSlow() {
        super("NoSlow", Category.MOVEMENT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        releaseItemSent = false;
        this.releaseTicksRemaining = 0;
        this.reset();
        this.stopBlink();
        this.bowActive = false;
        this.bowDelay = false;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.release();
        this.stopBlink();
        releaseItemSent = false;
        this.didSwapHand = false;
        this.shouldReleaseItem = false;
        this.pendingUseHand = null;
        this.releaseTicksRemaining = 0;
        this.bowActive = false;
        this.bowDelay = false;
        this.restoreUseKeyState();
        super.onDisable();
    }

    @EventTarget
    public void onSlowdown(SlowdownEvent event) {
        if (mc.player == null || !mc.player.isUsingItem()) return;
        ItemStack stack = mc.player.getUseItem();
        if (stack.isEmpty()) return;
        // 食物/药水/盾牌: 不依赖 Bow 开关, 始终取消减速(vanilla 吃, 不换手, 否则快吃完时换手打断最后几口吃不上)
        if (this.isEatOrDrink(stack) || stack.getUseAnimation() == UseAnim.BLOCK) {
            event.setSlowDown(false);
            mc.player.setSprinting(true);
            return;
        }
        // res 弓: 弓类使用中(bowActive)由状态机取消减速
        if (this.bowActive) {
            event.setSlowDown(false);
            mc.player.setSprinting(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null) {
            this.release();
            this.stopBlink();
            return;
        }
        // 桌面 һoе: Scaffold 开启或主手持末影珍珠时 NoSlow 不干预(避免冲突), 复位
        if ((step != Step.NONE || this.bowActive)
                && ((Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled())
                    || mc.player.getMainHandItem().is(Items.ENDER_PEARL))) {
            this.release();
            return;
        }
        if (this.isBlinking) {
            ++this.blinkTicks;
        }
        // res 弓兜底: 不再使用弓类(或已松开) -> 结束延迟, 重放入站包
        if (this.bowActive && (mc.player.getUseItem().isEmpty()
                || !this.isBowLike(mc.player.getUseItem().getUseAnimation()))) {
            this.bowActive = false;
            this.bowDelay = false;
            this.flushInboundQueue();
        }
        // NoC0FNoSlow: offhand state machine tick logic
        if (step != Step.NONE && step != Step.EATING) {
            mc.options.keyUse.setDown(false);
        }

        if (step == Step.NONE) {
                if (mc.player.isUsingItem()
                        && mc.options.keyUse.isDown()
                        && isUsable(mc.player.getUseItem().getUseAnimation())) {
                    if (isLookingAtInteractableBlock()) {
                        return;
                    }
                    InteractionHand hand = mc.player.getUsedItemHand();
                    if (hand == InteractionHand.OFF_HAND
                            || (hand == InteractionHand.MAIN_HAND && mc.player.getOffhandItem().isEmpty())) {
                        step = Step.ARMED;
                        mc.options.keyUse.setDown(false);

                        if (mc.player.containerMenu != mc.player.inventoryMenu) {
                            mc.getConnection().send(
                                    new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
                        }
                    }
                }
            } else if (step == Step.EATING) {
                if (mc.player.isUsingItem()) {
                    noUseTicks = 0;
                } else {
                    noUseTicks++;
                    if (noUseTicks >= 5) {
                        release();
                    }
                }
            } else {
                noUseTicks = 0;
            }
        if (this.releaseTicksRemaining > 0) {
            this.releaseUseKey();
            --this.releaseTicksRemaining;
            if (this.releaseTicksRemaining == 0) {
                this.restoreUseKeyState();
            }
        }
        if (this.pendingUseHand != null) {
            this.startUseItem(this.pendingUseHand, this.pendingUseCount);
            this.pendingUseHand = null;
            this.pendingUseCount = 0;
        }
        if (this.isBlinking && this.blinkTicks >= this.blinkDuration) {
            this.finishBlink();
            return;
        }
        if (this.didSwapHand && !this.isBlinking) {
            if (this.useHand != this.lastUseHand) {
                this.sendSwapOffhand();
            }
            releaseItemSent = true;
            this.didSwapHand = false;
            this.shouldReleaseItem = false;
            this.releaseTicksRemaining = 1;
            this.releaseUseKey();
            PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
            return;
        }
        if (this.shouldReleaseItem
                && mc.player.isUsingItem() && this.canSwapHands()) {
            this.shouldReleaseItem = false;
            this.startUseItemDefault(mc.player.getUsedItemHand());
        }
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (event.isPre() && this.isBlinking && this.blinkTicks >= this.blinkDuration && !this.didSwapHand) {
            this.stopBlink();
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.player == null) {
            return;
        }
        if (event.isIncoming() && this.shouldQueuePacket(event.getPacket())) {
            event.setCancelled(true);
            return;
        }
        Packet<?> p = event.getPacket();

        if (!event.isIncoming()) {
            // Send packet handling - NoC0F: use incoming C0F as trigger, not outgoing pong
            if (p instanceof ServerboundPlayerActionPacket action
                    && action.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
                if (step == Step.EATING) {
                    release();
                }
                // res 弓: 松开弓类 -> 结束延迟, 重放入站包
                if (this.bowActive) {
                    this.bowActive = false;
                    this.bowDelay = false;
                    this.flushInboundQueue();
                }
            }
        } else {
                // NoC0F: intercept C0F (ClientboundPingPacket) as swap timing signal + queue for replay
                if (step != Step.NONE && p instanceof ClientboundPingPacket) {
                    event.setCancelled(true);
                    queueInboundPacket(p);
                    if (step == Step.ARMED && !swapInArmed) {
                        swapInArmed = true;
                        hasSwapped = true;
                        mc.getConnection().send(new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                    }
                }

                // Queue velocity for player during noslow, re-process on release
                if (step != Step.NONE && p instanceof ClientboundSetEntityMotionPacket motion
                        && mc.player != null && motion.getId() == mc.player.getId()) {
                    event.setCancelled(true);
                    queueInboundPacket(p);
                }

                if (step == Step.ARMED && swapInArmed && p instanceof ClientboundContainerSetSlotPacket) {
                    swapInArmed = false;
                    mc.options.keyUse.setDown(true);
                    step = Step.EATING;
                }

            if (step != Step.NONE && p instanceof ClientboundPlayerPositionPacket) {
                release();
            }
        }
        if (event.getPacket() instanceof ServerboundPlayerActionPacket actionPacket
                && actionPacket.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
            this.blinkTicks = Math.max(this.blinkTicks, 1);
        }
        if (event.getPacket() instanceof ServerboundUseItemOnPacket useOnPacket
                && this.didSwapHand
                && useOnPacket.getHand() == this.useHand
                && mc.player.getInventory().selected == this.swapInitSlot) {
            InteractionHand other = useOnPacket.getHand() == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            PacketUtil.sendQueued(new ServerboundUseItemOnPacket(other, useOnPacket.getHitResult(), useOnPacket.getSequence()));
        }
        if (event.getPacket() instanceof ServerboundUseItemPacket usePacket) {
            if (this.didSwapHand || this.releaseTicksRemaining > 0) {
                event.setCancelled(true);
            } else {
                ItemStack handStack = mc.player.getItemInHand(usePacket.getHand());
                // res һesсјјp 弓分支(jсоijсі(false)): 弓类不换手, 标记取消减速 + 延迟入站包, 放行 UseItem
                // 食物/药水/盾牌: 走 vanilla(不换手不打断), 由 onSlowdown 取消减速
                if (this.isBowLike(handStack.getUseAnimation())) {
                    this.handleBowUseItem(handStack);
                }
            }
        }
    }

    private void startUseItem(InteractionHand hand, int count) {
        if (mc.player == null) return;
        if (isLookingAtInteractableBlock()) {
            return;
        }
        this.didSwapHand = true;
        this.lastUseHand = hand;
        this.swapInitSlot = mc.player.getInventory().selected;
        this.useHand = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        this.sendSwapOffhand();
        if (count > 0) {
            PacketUtil.sendQueued(new ServerboundUseItemPacket(this.useHand, count));
        } else {
            PacketUtil.sendPredictiveDirect(seq -> new ServerboundUseItemPacket(this.useHand, seq));
        }
        this.startBlink(2);
    }

    private void startUseItemDefault(InteractionHand hand) {
        this.startUseItem(hand, 0);
    }

    private void finishBlink() {
        this.shouldReleaseItem = false;
        if (!this.isBlinking || !this.didSwapHand || mc.player == null) return;
        if (this.useHand != this.lastUseHand) {
            this.sendSwapOffhand();
        }
        releaseItemSent = true;
        this.didSwapHand = false;
        this.releaseTicksRemaining = 1;
        this.releaseUseKey();
        PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
    }

    private boolean canSwapHands() {
        if (mc.player == null) return false;
        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();
        if (mainHand.isEmpty() || offHand.isEmpty()) return true;
        if (mainHand.getItem() == Items.ENCHANTED_GOLDEN_APPLE && offHand.getItem() == Items.GOLDEN_APPLE) return false;
        if (offHand.getItem() == Items.ENCHANTED_GOLDEN_APPLE && mainHand.getItem() == Items.GOLDEN_APPLE) return false;
        return mainHand.getItem() != offHand.getItem();
    }

    public static boolean isBlocking(Minecraft minecraft) {
        return INSTANCE != null && INSTANCE.isBlockingInternal(minecraft);
    }

    private boolean isBlockingInternal(Minecraft minecraft) {
        if (!this.isEnabled()) return false;
        if (minecraft == null || minecraft.player == null || minecraft.hitResult == null) return false;
        if (minecraft.hitResult.getType() != HitResult.Type.BLOCK) return false;
        for (InteractionHand hand : InteractionHand.values()) {
            if (isUsable(minecraft.player.getItemInHand(hand).getUseAnimation())) return true;
        }
        return false;
    }

    // ===== NoC0FNoSlow methods =====

    public static boolean isProcessing() {
        return INSTANCE != null && INSTANCE.step != Step.NONE;
    }

    /**
     * NoSlow 是否正在实际处理使用物品(而非仅开启模块): NoC0F 换手状态机
     * (吃食物/药水/盾牌)、弓类延迟入站包期间、或换手/释放收尾中。
     * 供 InventoryManager 等判定是否暂停其它发包行为——NoSlow 工作时会发
     * SWAP_ITEM_WITH_OFFHAND/RELEASE_USE_ITEM 并延迟入站包,整理 click 与之
     * 同 tick 会触发反作弊,且 NoSlow 工作期间保持疾跑,与整理压疾跑冲突。
     */
    public static boolean isActive() {
        if (INSTANCE == null) return false;
        return INSTANCE.step != Step.NONE
                || INSTANCE.bowActive
                || INSTANCE.didSwapHand
                || INSTANCE.releaseTicksRemaining > 0;
    }

    private void reset() {
        step = Step.NONE;
        hasSwapped = false;
        swapInArmed = false;
        noUseTicks = 0;
        cached.clear();
    }

    private void release() {
        if (step == Step.NONE && cached.isEmpty() && !hasSwapped) {
            return;
        }
        step = Step.NONE;
        noUseTicks = 0;
        swapInArmed = false;
        if (mc.player == null || mc.getConnection() == null) {
            cached.clear();
            inboundQueue.clear();
            return;
        }
        while (!cached.isEmpty()) {
            mc.getConnection().send(cached.poll());
        }
        flushInboundQueue();
        if (hasSwapped) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            hasSwapped = false;
        }
    }

    private boolean isUsable(UseAnim action) {
        return action == UseAnim.EAT || action == UseAnim.DRINK || action == UseAnim.SPEAR;
    }
    private boolean isLookingAtInteractableBlock() {
        return isLookingAtInteractableBlock(mc);
    }

    public static boolean isLookingAtInteractableBlock(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return false;
        if (!(minecraft.hitResult instanceof BlockHitResult blockHit)) return false;

        BlockPos pos = blockHit.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);

        // 通过方块标签检测可交互方块家族
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.BUTTONS) || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.SHULKER_BOXES) || state.is(BlockTags.ANVIL)
                || state.is(BlockTags.BEDS) || state.is(BlockTags.CAMPFIRES)) {
            return true;
        }

        // 检测已知的可交互方块
        Block block = state.getBlock();
        return block == Blocks.CHEST
                || block == Blocks.TRAPPED_CHEST
                || block == Blocks.FURNACE
                || block == Blocks.BLAST_FURNACE
                || block == Blocks.SMOKER
                || block == Blocks.BARREL
                || block == Blocks.ENDER_CHEST
                || block == Blocks.BREWING_STAND
                || block == Blocks.HOPPER
                || block == Blocks.DISPENSER
                || block == Blocks.DROPPER
                || block == Blocks.JUKEBOX
                || block == Blocks.NOTE_BLOCK
                || block == Blocks.LEVER
                || block == Blocks.REPEATER
                || block == Blocks.COMPARATOR
                || block == Blocks.DAYLIGHT_DETECTOR
                || block == Blocks.CAKE
                || block == Blocks.COMPOSTER
                || block == Blocks.BEEHIVE
                || block == Blocks.BEE_NEST
                || block == Blocks.RESPAWN_ANCHOR
                || block == Blocks.GRINDSTONE
                || block == Blocks.STONECUTTER
                || block == Blocks.CARTOGRAPHY_TABLE
                || block == Blocks.LOOM
                || block == Blocks.SMITHING_TABLE
                || block == Blocks.LECTERN
                || block == Blocks.BELL
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.CRAFTING_TABLE
                || block == Blocks.ENCHANTING_TABLE;
    }

    private boolean isEatOrDrink(ItemStack stack) {
        if (stack.isEmpty()) return false;
        UseAnim anim = stack.getUseAnimation();
        Item item = stack.getItem();
        return anim == UseAnim.EAT || anim == UseAnim.DRINK || item instanceof PotionItem;
    }

    // res pјіеoc 弓类判定: BOW/CROSSBOW/SPEAR
    private boolean isBowLike(UseAnim anim) {
        return anim == UseAnim.BOW || anim == UseAnim.CROSSBOW || anim == UseAnim.SPEAR;
    }

    // res һoіo: 煲类(不换手)
    private boolean isStew(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item == Items.MUSHROOM_STEW || item == Items.RABBIT_STEW
                || item == Items.BEETROOT_SOUP || item == Items.SUSPICIOUS_STEW;
    }

    // res һesсјјp 弓分支(jсоijсі(false)): 弓类不换手, 只标记取消减速 + 延迟入站包, UseItem 放行
    private void handleBowUseItem(ItemStack stack) {
        if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
            this.shouldReleaseItem = false;
            return;
        }
        if (this.isStew(stack)) {
            this.shouldReleaseItem = false;
            return;
        }
        if (this.isLookingAtInteractableBlock()) {
            this.shouldReleaseItem = false;
            return;
        }
        this.shouldReleaseItem = false;
        this.bowActive = true;
        this.bowDelay = true;
    }

    private void sendSwapOffhand() {
        PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
    }

    private void startBlink(int duration) {
        if (!this.isBlinking) this.blinkTicks = 0;
        this.isBlinking = true;
        this.blinkDuration = duration;
    }

    private void stopBlink() {
        this.flushInboundQueue();
        this.isBlinking = false;
        this.blinkTicks = 0;
        this.blinkDuration = 0;
    }

    private boolean shouldQueuePacket(Packet<?> packet) {
        if ((!this.isBlinking && !this.bowDelay) || packet == null || mc.level == null || mc.getConnection() == null) return false;
        if (packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundLoginPacket
                || packet instanceof ClientboundRespawnPacket) {
            this.stopBlink();
            return false;
        }
        if (packet instanceof ClientboundEntityEventPacket evt) {
            Entity entity = evt.getEntity(mc.level);
            if (entity != null && (entity != mc.player || evt.getEventId() != 2)) return false;
        }
        if (!this.isBlinkablePacket(packet)) return false;
        this.queueInboundPacket(packet);
        return true;
    }

    private void queueInboundPacket(Packet<?> packet) {
        @SuppressWarnings("unchecked")
        Packet<ClientGamePacketListener> typed = (Packet<ClientGamePacketListener>) packet;
        this.inboundQueue.add(typed);
    }

    private void flushInboundQueue() {
        if (mc == null || mc.getConnection() == null) {
            this.inboundQueue.clear();
            return;
        }
        while (!this.inboundQueue.isEmpty()) {
            Packet<ClientGamePacketListener> packet = this.inboundQueue.poll();
            try {
                packet.handle(mc.getConnection());
            } catch (Exception e) {
                this.inboundQueue.clear();
                logger.error("Failed to flush packet", e);
                return;
            }
        }
    }

    private boolean isBlinkablePacket(Packet<?> packet) {
        if (packet instanceof ClientboundKeepAlivePacket || packet instanceof ClientboundPingPacket) {
            return true;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            return mc.player != null && motion.getId() == mc.player.getId();
        }
        if (packet instanceof ClientboundContainerSetSlotPacket slotPacket) {
            return slotPacket.getSlot() == 45 || slotPacket.getContainerId() == 0;
        }
        if (packet instanceof ClientboundSetEquipmentPacket equipmentPacket) {
            for (Pair<EquipmentSlot, ItemStack> slot : equipmentPacket.getSlots()) {
                if (slot.getFirst() == EquipmentSlot.OFFHAND) {
                    return true;
                }
            }
        }
        return false;
    }

    private void releaseUseKey() {
        mc.options.keyUse.setDown(false);
        while (mc.options.keyUse.consumeClick()) {
        }
    }

    private void restoreUseKeyState() {
        if (mc == null || mc.options == null || mc.getWindow() == null) return;
        InputConstants.Key key = InputConstants.getKey(mc.options.keyUse.saveString());
        long window = mc.getWindow().getWindow();
        boolean down = key.getType() == InputConstants.Type.MOUSE
                ? GLFW.glfwGetMouseButton(window, key.getValue()) == 1
                : InputConstants.isKeyDown(window, key.getValue());
        mc.options.keyUse.setDown(down);
    }

    private Packet<?> createUseItemPacket(int sequence) {
        return new ServerboundUseItemPacket(this.useHand, sequence);
    }
}