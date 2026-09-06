package client.nilore.modules.impl.player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.FurnaceMenu;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.animation.Timer;
import client.nilore.utils.game.BlockUtil;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.misc.ReflectionUtil;
import client.nilore.event.EventTarget;

public class ChestStealer
extends Module {

    public record StealTarget(int slotIndex, ItemStack itemStack, int priority, double score) {
    }

    public static ChestStealer INSTANCE;
    private static final Timer actionTimer;
    private final NumberSetting clickDelaySetting = new NumberSetting("Delay", 110, 0, 1000, 10);
    private final NumberSetting openDelaySetting = new NumberSetting("Open Delay", 2, 0, 10, 1);
    private final BooleanSetting chestSetting = new BooleanSetting("Chest", true);
    private final BooleanSetting enderChestSetting = new BooleanSetting("Ender Chest", false);
    private final BooleanSetting furnaceSetting = new BooleanSetting("Furnace", true);
    private final BooleanSetting brewingStandSetting = new BooleanSetting("BrewingStand", true);
    private final BooleanSetting pickTrashSetting = new BooleanSetting("PickTrash", false);
    private final BooleanSetting onlyBestSetting = new BooleanSetting("Only Best", true);
    private final BooleanSetting randomClickSetting = new BooleanSetting("Random Click", true);
    private final BooleanSetting smartStealingSetting = new BooleanSetting("Smart Stealing", true);
    private final ModeSetting clickMode = new ModeSetting("Click Mode", "Windows Click", "Packet").withDefault("Windows Click");
    private static final Timer stealTimer;
    private static final Timer openTimer;
    private final Random random = new Random();
    private AbstractContainerMenu pendingMenu = null;
    private boolean hasPendingClick = false;
    private int totalBlockCount = 0;
    private int pendingSlot = -1;
    private int ticksSinceMenu = 0;
    private static long clickDelayMs;
    private int accessCount;
    private Screen lastScreen;
    private int openDelayTicks = 0;
    private final List<ChestStealer.StealTarget> stealTargetQueue = new ArrayList<>();
    private int stealIndex = 0;
    private boolean queueBuilt = false;

    public ChestStealer() {
        super("ChestStealer", Category.PLAYER);
        INSTANCE = this;
    }

    public static boolean isRateLimited() {
        return !stealTimer.hasPassed(100L) && !openTimer.hasPassed((int)clickDelayMs);
    }

    @Override
    public void onDisable() {
        this.resetAll();
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        this.resetAll();
    }

    @EventTarget
    public void onGameTick(GameTickEvent gameTickEvent) {
        if (this.hasPendingClick && this.pendingMenu != null && this.pendingSlot >= 0) {
            ++this.ticksSinceMenu;
            if (this.ticksSinceMenu >= 1) {
                this.executePendingClick();
                this.resetState();
            }
        }
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (mc == null || mc.player == null || mc.level == null || mc.gameMode == null
                || mc.getConnection() == null || KillAura.target != null || Scaffold.INSTANCE.isEnabled()) {
            return;
        }
        if (!openTimer.hasPassed((int) clickDelayMs)
                || !mc.player.isAlive() || mc.player.isDeadOrDying()
                || mc.player.isSpectator() || motionEvent.isPre()) {
            return;
        }
        Screen screen = mc.screen;
        AbstractContainerMenu containerMenu = mc.player.containerMenu;
        this.countBlocks();
        if (screen instanceof ContainerScreen containerScreen) {
            if (screen != this.lastScreen) {
                actionTimer.reset();
                this.openDelayTicks = 0;
                this.queueBuilt = false;
                this.stealTargetQueue.clear();
                this.stealIndex = 0;
            } else {
                ++this.openDelayTicks;
                if (this.openDelayTicks < this.openDelaySetting.getValue().intValue()) {
                    return;
                }
                String title = containerScreen.getTitle().getString();
                String chestTitle = Component.translatable("container.chest").getString();
                String doubleChestTitle = Component.translatable("container.chestDouble").getString();
                String enderChestTitle = Component.translatable("container.enderchest").getString();
                ChestMenu chestMenu = containerScreen.getMenu();
                if (this.chestSetting.getValue() && (title.equals(chestTitle) || title.equals(doubleChestTitle) || title.equals("Chest"))) {
                    if (this.shouldCloseChest(chestMenu)) {
                        this.stealFromChest(chestMenu);
                    }
                } else if (this.enderChestSetting.getValue() && title.equals(enderChestTitle) && this.shouldCloseChest(chestMenu)) {
                    this.stealFromChest(chestMenu);
                }
            }
        } else {
            this.openDelayTicks = 0;
            this.queueBuilt = false;
            this.stealTargetQueue.clear();
            this.stealIndex = 0;
        }
        if (containerMenu instanceof FurnaceMenu furnaceMenu) {
            if (this.furnaceSetting.getValue()) {
                this.stealFromFurnace(furnaceMenu);
            }
        }
        if (containerMenu instanceof BrewingStandMenu brewingMenu) {
            if (this.brewingStandSetting.getValue()) {
                this.stealFromBrewing(brewingMenu);
            }
        }
        this.lastScreen = screen;
    }

    private boolean shouldCloseChest(ChestMenu chestMenu) {
        if (this.isChestDone(chestMenu) && stealTimer.hasPassed(100L)) {
            mc.player.closeContainer();
            return false;
        }
        return true;
    }

    private void stealFromChest(ChestMenu chestMenu) {
        ++this.accessCount;
        if (this.smartStealingSetting.getValue() && this.accessCount > 1) {
            this.stealSmartMode(chestMenu);
        } else {
            this.stealRandomMode(chestMenu);
        }
    }

    private void stealSmartMode(ChestMenu chestMenu) {
        if (!this.queueBuilt) {
            this.buildStealQueue(chestMenu);
            this.queueBuilt = true;
            this.stealIndex = 0;
        }
        if (this.stealIndex < this.stealTargetQueue.size()) {
            ChestStealer.StealTarget target = this.stealTargetQueue.get(this.stealIndex);
            if (!chestMenu.getSlot(target.slotIndex).getItem().isEmpty()) {
                this.schedulePendingClick(chestMenu, target.slotIndex);
                ++this.stealIndex;
            } else {
                ++this.stealIndex;
            }
        } else if (this.isChestComplete(chestMenu) && stealTimer.hasPassed(100L)) {
            mc.player.closeContainer();
        }
    }

    private void buildStealQueue(ChestMenu chestMenu) {
        ArrayList<ChestStealer.StealTarget> candidates = new ArrayList<>();
        for (int slot = 0; slot < chestMenu.getRowCount() * 9; ++slot) {
            ItemStack itemStack = chestMenu.getSlot(slot).getItem();
            if (itemStack.isEmpty() || !this.shouldStealItem(itemStack)) continue;
            int priority = this.getItemPriority(itemStack);
            double score = this.getItemScore(itemStack);
            candidates.add(new ChestStealer.StealTarget(slot, itemStack, priority, score));
        }
        Map<String, List<ChestStealer.StealTarget>> categoryMap = this.categorizeItems(candidates);
        this.stealTargetQueue.clear();
        List<String> categories = Arrays.asList("god", "helmet", "chestplate", "leggings", "boots", "sword", "bow", "crossbow", "golden_apple", "pickaxe", "axe", "shovel", "special", "utility", "other");
        for (String category : categories) {
            if (!categoryMap.containsKey(category)) continue;
            List<ChestStealer.StealTarget> categoryItems = categoryMap.get(category);
            boolean isBestOnlyCategory = category.equals("god") || category.equals("helmet") || category.equals("chestplate") || category.equals("leggings") || category.equals("boots") || category.equals("sword") || category.equals("bow") || category.equals("crossbow") || category.equals("pickaxe") || category.equals("axe") || category.equals("shovel");
            if (this.onlyBestSetting.getValue() && isBestOnlyCategory) {
                ChestStealer.StealTarget best = categoryItems.stream().max(Comparator.comparingDouble(t -> t.score)).orElse(null);
                if (best == null) continue;
                this.stealTargetQueue.add(best);
                continue;
            }
            categoryItems.sort((a, b) -> Double.compare(b.score, a.score));
            this.stealTargetQueue.addAll(categoryItems);
        }
    }

    private Map<String, List<ChestStealer.StealTarget>> categorizeItems(List<ChestStealer.StealTarget> targets) {
        HashMap<String, List<ChestStealer.StealTarget>> categoryMap = new HashMap<>();
        for (ChestStealer.StealTarget target : targets) {
            String category = this.getItemCategory(target.itemStack);
            categoryMap.computeIfAbsent(category, key -> new ArrayList<>()).add(target);
        }
        return categoryMap;
    }

    private String getItemCategory(ItemStack itemStack) {
        Item item = itemStack.getItem();
        if (ItemUtil.isWeaponItem(itemStack) || ItemUtil.isOtherCheat(itemStack)) {
            return "god";
        }
        if (item instanceof ArmorItem armorItem) {
            return switch (armorItem.getEquipmentSlot()) {
                case HEAD -> "helmet";
                case CHEST -> "chestplate";
                case LEGS -> "leggings";
                case FEET -> "boots";
                default -> "other";
            };
        }
        if (item instanceof SwordItem) {
            return "sword";
        }
        if (item instanceof BowItem) {
            return "bow";
        }
        if (item instanceof CrossbowItem) {
            return "crossbow";
        }
        if (item instanceof PickaxeItem) {
            return "pickaxe";
        }
        if (item instanceof AxeItem) {
            return "axe";
        }
        if (item instanceof ShovelItem) {
            return "shovel";
        }
        if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
            return "golden_apple";
        }
        if (item == Items.COMPASS || item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET) {
            return "special";
        }
        if (item == Items.COBWEB) {
            return "utility";
        }
        if (item == Items.ENDER_PEARL || item == Items.SNOWBALL || item == Items.EGG || item == Items.ARROW || item instanceof FishingRodItem || item instanceof BlockItem) {
            return "utility";
        }
        return "other";
    }

    private int getItemPriority(ItemStack itemStack) {
        Item item = itemStack.getItem();
        if (ItemUtil.isWeaponItem(itemStack) || ItemUtil.isOtherCheat(itemStack)) {
            return 150;
        }
        if (item instanceof ArmorItem armorItem) {
            return switch (armorItem.getEquipmentSlot()) {
                case HEAD -> 100;
                case CHEST -> 99;
                case LEGS -> 98;
                case FEET -> 97;
                default -> 50;
            };
        }
        if (item instanceof SwordItem) {
            return 95;
        }
        if (item instanceof BowItem) {
            return 93;
        }
        if (item instanceof CrossbowItem) {
            return 92;
        }
        if (item == Items.ENCHANTED_GOLDEN_APPLE) {
            return 91;
        }
        if (item == Items.GOLDEN_APPLE) {
            return 90;
        }
        if (item instanceof PickaxeItem) {
            return 89;
        }
        if (item instanceof AxeItem) {
            return 88;
        }
        if (item instanceof ShovelItem) {
            return 87;
        }
        if (item == Items.COMPASS) {
            return 85;
        }
        if (item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET) {
            return 83;
        }
        if (item == Items.ENDER_PEARL) {
            return 80;
        }
        if (item == Items.ARROW) {
            return 75;
        }
        if (item == Items.COBWEB) {
            return 72;
        }
        if (item == Items.SNOWBALL || item == Items.EGG) {
            return 70;
        }
        if (item instanceof FishingRodItem) {
            return 65;
        }
        if (item instanceof BlockItem) {
            return 60;
        }
        return 50;
    }

    private double getItemScore(ItemStack itemStack) {
        Item item = itemStack.getItem();
        if (ItemUtil.isWeaponItem(itemStack) || ItemUtil.isOtherCheat(itemStack)) {
            return 10000.0;
        }
        if (item instanceof ArmorItem) {
            return ItemUtil.getArmorScore(itemStack);
        }
        if (item instanceof SwordItem) {
            return ItemUtil.getSwordDamage(itemStack);
        }
        if (item instanceof AxeItem && ItemUtil.isLegitAxe(itemStack)) {
            return ItemUtil.getAxeDamage(itemStack);
        }
        if (item instanceof DiggerItem) {
            return ItemUtil.getDigSpeed(itemStack);
        }
        if (item instanceof BowItem) {
            if (ItemUtil.isGoodBow(itemStack)) {
                return ItemUtil.getBowScore(itemStack);
            }
            if (ItemUtil.isGoodBowAlt(itemStack)) {
                return ItemUtil.getBowScoreAlt(itemStack);
            }
            return 1.0;
        }
        if (item instanceof CrossbowItem) {
            return ItemUtil.getCrossbowScore(itemStack);
        }
        if (item == Items.ENCHANTED_GOLDEN_APPLE) {
            return 50.0 + (double)itemStack.getCount();
        }
        if (item == Items.GOLDEN_APPLE) {
            return 30.0 + (double)itemStack.getCount();
        }
        if (item == Items.ENDER_PEARL) {
            return 10.0 + (double)itemStack.getCount();
        }
        if (item == Items.ARROW) {
            return 5.0 + (double)itemStack.getCount() * 0.1;
        }
        if (item == Items.COBWEB) {
            return 4.0 + (double)itemStack.getCount() * 0.1;
        }
        if (item == Items.SNOWBALL || item == Items.EGG) {
            return 3.0 + (double)itemStack.getCount() * 0.1;
        }
        if (item instanceof FishingRodItem) {
            return ItemUtil.getDigSpeed(itemStack);
        }
        if (item instanceof BlockItem) {
            return 2.0 + (double)itemStack.getCount() * 0.05;
        }
        return 1.0;
    }

    private void stealRandomMode(ChestMenu chestMenu) {
        List<Integer> stealableSlots = this.getStealableChestSlots(chestMenu);
        if (this.randomClickSetting.getValue() && !stealableSlots.isEmpty() && this.accessCount > 1) {
            int randomSlot = stealableSlots.get(this.random.nextInt(stealableSlots.size()));
            this.schedulePendingClick(chestMenu, randomSlot);
        } else {
            for (int slot = 0; slot < chestMenu.getRowCount() * 9; ++slot) {
                ItemStack itemStack = chestMenu.getSlot(slot).getItem();
                if (itemStack.isEmpty() || this.accessCount <= 1 || !this.tryStealSlot(chestMenu, slot)) continue;
                return;
            }
        }
    }

    private void stealFromFurnace(FurnaceMenu furnaceMenu) {
        ++this.accessCount;
        try {
            Container container = this.getFurnaceContainer(furnaceMenu);
            if (container == null) {
                return;
            }
            if (this.isFurnaceDone(furnaceMenu) && stealTimer.hasPassed(100L)) {
                mc.player.closeContainer();
                return;
            }
            List<Integer> stealableSlots = this.getStealableContainerSlots(container);
            if (this.randomClickSetting.getValue() && !stealableSlots.isEmpty() && this.accessCount > 1) {
                int randomSlot = stealableSlots.get(this.random.nextInt(stealableSlots.size()));
                this.schedulePendingClick(furnaceMenu, randomSlot);
            } else {
                for (int slot = 0; slot < container.getContainerSize(); ++slot) {
                    ItemStack itemStack = container.getItem(slot);
                    if (itemStack.isEmpty() || this.accessCount <= 1 || !this.shouldStealItem(itemStack)) continue;
                    this.schedulePendingClick(furnaceMenu, slot);
                    return;
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private void stealFromBrewing(BrewingStandMenu brewingStandMenu) {
        ++this.accessCount;
        Container container = ReflectionUtil.getBrewingStand(brewingStandMenu);
        if (container == null) {
            return;
        }
        if (this.isBrewingDone(brewingStandMenu) && stealTimer.hasPassed(100L)) {
            mc.player.closeContainer();
            return;
        }
        List<Integer> stealableSlots = this.getStealableContainerSlots(container);
        if (this.randomClickSetting.getValue() && !stealableSlots.isEmpty() && this.accessCount > 1) {
            int randomSlot = stealableSlots.get(this.random.nextInt(stealableSlots.size()));
            this.schedulePendingClick(brewingStandMenu, randomSlot);
        } else {
            for (int slot = 0; slot < container.getContainerSize(); ++slot) {
                ItemStack itemStack = container.getItem(slot);
                if (itemStack.isEmpty() || this.accessCount <= 1 || !this.shouldStealItem(itemStack)) continue;
                this.schedulePendingClick(brewingStandMenu, slot);
                return;
            }
        }
    }

    private List<Integer> getStealableChestSlots(ChestMenu chestMenu) {
        ArrayList<Integer> stealableSlots = new ArrayList<>();
        for (int slot = 0; slot < chestMenu.getRowCount() * 9; ++slot) {
            ItemStack itemStack = chestMenu.getSlot(slot).getItem();
            if (itemStack.isEmpty() || !ChestStealer.isWorthStealing(itemStack) && !this.pickTrashSetting.getValue() || !this.shouldStealItem(itemStack)) continue;
            stealableSlots.add(slot);
        }
        return stealableSlots;
    }

    private List<Integer> getStealableContainerSlots(Container container) {
        ArrayList<Integer> stealableSlots = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); ++slot) {
            ItemStack itemStack = container.getItem(slot);
            if (itemStack.isEmpty() || !this.shouldStealItem(itemStack)) continue;
            stealableSlots.add(slot);
        }
        return stealableSlots;
    }

    private Container getFurnaceContainer(AbstractFurnaceMenu furnaceMenu) throws Exception {
        Field[] fields;
        for (Field field : fields = AbstractFurnaceMenu.class.getDeclaredFields()) {
            if (!Container.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            return (Container)field.get(furnaceMenu);
        }
        return null;
    }

    private void schedulePendingClick(AbstractContainerMenu menu, int slot) {
        if (!this.hasPendingClick) {
            this.pendingMenu = menu;
            this.pendingSlot = slot;
            this.hasPendingClick = true;
            this.ticksSinceMenu = 0;
            this.executePendingClick();
            this.resetState();
        }
    }

    private void executePendingClick() {
        if (this.pendingMenu != null && this.pendingSlot >= 0) {
            clickDelayMs = this.clickDelaySetting.getValue().longValue();
            if (clickMode.is("Packet")) {
                // 对齐 res Stealer clickSlot / handleInventoryMouseClick: 先本地应用点击
                // (同步容器槽位/光标状态), 再以 Packet 形式发出 —— 避免裸发包导致本地容器
                // 状态失步, 使后续 Smart 判空/连点选中与实际一致。stateId 需在 clicked() 之后读
                // (clicked 会推进本地 stateId), 与 vanilla 发包时序一致。
                ItemStack carriedItem = this.pendingMenu.getCarried().copy();
                Int2ObjectMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
                for (int i = 0; i < this.pendingMenu.slots.size(); ++i) {
                    ItemStack slotStack = this.pendingMenu.slots.get(i).getItem();
                    if (!slotStack.isEmpty()) {
                        changedSlots.put(this.pendingMenu.slots.get(i).index, slotStack.copy());
                    }
                }
                if (this.pendingSlot >= 0 && this.pendingSlot < this.pendingMenu.slots.size()) {
                    this.pendingMenu.clicked(this.pendingSlot, 0, ClickType.QUICK_MOVE, mc.player);
                    int stateId = this.pendingMenu.getStateId();
                    PacketUtil.sendQueued(new ServerboundContainerClickPacket(this.pendingMenu.containerId, stateId, this.pendingSlot, 0, ClickType.QUICK_MOVE, carriedItem, changedSlots));
                }
            } else {
                mc.gameMode.handleInventoryMouseClick(this.pendingMenu.containerId, this.pendingSlot, 0, ClickType.QUICK_MOVE, mc.player);
            }
            openTimer.reset();
            stealTimer.reset();
            actionTimer.reset();
        }
    }

    private boolean tryStealSlot(ChestMenu chestMenu, int slot) {
        ItemStack itemStack = chestMenu.getSlot(slot).getItem();
        if ((ChestStealer.isWorthStealing(itemStack) || this.pickTrashSetting.getValue()) && this.shouldStealItem(itemStack)) {
            this.schedulePendingClick(chestMenu, slot);
            return true;
        }
        return false;
    }

    private void resetAll() {
        this.resetState();
        this.openDelayTicks = 0;
    }

    private void resetState() {
        this.hasPendingClick = false;
        this.pendingSlot = -1;
        this.pendingMenu = null;
        this.ticksSinceMenu = 0;
    }

    private void countBlocks() {
        this.totalBlockCount = 0;
        for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); ++slot) {
            ItemStack itemStack = mc.player.getInventory().getItem(slot);
            if (itemStack.isEmpty() || !(itemStack.getItem() instanceof BlockItem)) continue;
            this.totalBlockCount += itemStack.getCount();
        }
    }

    private boolean shouldStealItem(ItemStack itemStack) {
        int count;
        Item item = itemStack.getItem();
        if (item instanceof FishingRodItem && (count = ItemUtil.countItem(Items.FISHING_ROD)) > 0) {
            return false;
        }
        if (item instanceof BlockItem && item != Items.COBWEB && item != Items.TNT) {
            count = InventoryManager.getMaxBlockSize();
            if (this.totalBlockCount + itemStack.getCount() > count) {
                return false;
            }
        }
        if (this.onlyBestSetting.getValue()) {
            if (ItemUtil.isWeaponItem(itemStack) || ItemUtil.isOtherCheat(itemStack)) {
                return true;
            }
            if (item instanceof SwordItem) {
                return this.isBetterThanCurrent(itemStack);
            }
            if (item instanceof DiggerItem) {
                return this.isBetterThanCurrent(itemStack);
            }
            if (item instanceof ArmorItem) {
                return this.isBetterThanCurrent(itemStack);
            }
            if (item instanceof BowItem) {
                return this.isBetterThanCurrent(itemStack);
            }
            if (item instanceof CrossbowItem) {
                return this.isBetterThanCurrent(itemStack);
            }
            if (!(item instanceof SwordItem || item instanceof DiggerItem || item instanceof ArmorItem || item instanceof BowItem || item instanceof CrossbowItem)) {
                return ChestStealer.isWorthStealing(itemStack) || this.pickTrashSetting.getValue() != false;
            }
        }
        return true;
    }

    private boolean isBetterThanCurrent(ItemStack itemStack) {
        if (itemStack.getItem() instanceof SwordItem) {
            float candidateDamage = ItemUtil.getSwordDamage(itemStack);
            float bestDamage = ItemUtil.getBestSwordDamage();
            return candidateDamage > bestDamage;
        }
        if (itemStack.getItem() instanceof DiggerItem) {
            if (itemStack.getItem() instanceof PickaxeItem) {
                float candidateSpeed = ItemUtil.getDigSpeed(itemStack);
                float bestSpeed = ItemUtil.getBestPickaxeScore();
                return candidateSpeed > bestSpeed;
            }
            if (itemStack.getItem() instanceof AxeItem) {
                if (ItemUtil.isLegitAxe(itemStack)) {
                    float candidateDamage = ItemUtil.getAxeDamage(itemStack);
                    ItemStack bestAxeStack = ItemUtil.getBestSharpAxe();
                    float bestDamage = bestAxeStack != null ? ItemUtil.getAxeDamage(bestAxeStack) : 0.0f;
                    return candidateDamage > bestDamage;
                }
                float candidateSpeed = ItemUtil.getDigSpeed(itemStack);
                float bestSpeed = ItemUtil.getBestAxeScore();
                return candidateSpeed > bestSpeed;
            }
            if (itemStack.getItem() instanceof ShovelItem) {
                float candidateSpeed = ItemUtil.getDigSpeed(itemStack);
                float bestSpeed = ItemUtil.getBestShovelScore();
                return candidateSpeed > bestSpeed;
            }
        } else {
            Item item = itemStack.getItem();
            if (item instanceof ArmorItem armorItem) {
                float candidateScore = ItemUtil.getArmorScore(itemStack);
                float equippedScore = ItemUtil.getEquippedArmorScore(armorItem.getEquipmentSlot());
                return candidateScore > equippedScore + 0.1f;
            }
            if (itemStack.getItem() instanceof BowItem) {
                if (ItemUtil.isGoodBow(itemStack)) {
                    float candidateScore = ItemUtil.getBowScore(itemStack);
                    float bestScore = ItemUtil.getBestBowScore();
                    return candidateScore > bestScore;
                }
                if (ItemUtil.isGoodBowAlt(itemStack)) {
                    float candidateScore = ItemUtil.getBowScoreAlt(itemStack);
                    float bestScore = ItemUtil.getBestBowScoreAlt();
                    return candidateScore > bestScore;
                }
            } else if (itemStack.getItem() instanceof CrossbowItem) {
                float candidateScore = ItemUtil.getCrossbowScore(itemStack);
                float bestScore = ItemUtil.getBestCrossbowScore();
                return candidateScore > bestScore;
            }
        }
        return true;
    }

    private boolean isChestDone(ChestMenu chestMenu) {
        for (int slot = 0; slot < chestMenu.getRowCount() * 9; ++slot) {
            ItemStack itemStack = chestMenu.getSlot(slot).getItem();
            if (itemStack.isEmpty() || !ChestStealer.isWorthStealing(itemStack) && !this.pickTrashSetting.getValue() || !this.shouldStealItem(itemStack)) continue;
            return false;
        }
        return true;
    }

    private boolean isChestComplete(ChestMenu chestMenu) {
        for (int slot = 0; slot < chestMenu.getRowCount() * 9; ++slot) {
            ItemStack itemStack = chestMenu.getSlot(slot).getItem();
            if (itemStack.isEmpty() || !ChestStealer.isWorthStealing(itemStack) && !this.pickTrashSetting.getValue() || !this.shouldStealItem(itemStack)) continue;
            return false;
        }
        return true;
    }

    private boolean isFurnaceDone(FurnaceMenu furnaceMenu) {
        try {
            Container container = this.getFurnaceContainer(furnaceMenu);
            if (container == null) {
                return false;
            }
            for (int slot = 0; slot < container.getContainerSize(); ++slot) {
                ItemStack itemStack = container.getItem(slot);
                if (itemStack.isEmpty() || !this.shouldStealItem(itemStack)) continue;
                return false;
            }
        } catch (Exception exception) {
            exception.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean isBrewingDone(BrewingStandMenu brewingStandMenu) {
        Container container = ReflectionUtil.getBrewingStand(brewingStandMenu);
        if (container == null) {
            return true;
        }
        for (int slot = 0; slot < container.getContainerSize(); ++slot) {
            ItemStack itemStack = container.getItem(slot);
            if (itemStack.isEmpty() || !this.shouldStealItem(itemStack)) continue;
            return false;
        }
        return true;
    }

    public static boolean isWorthStealing(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        if (ItemUtil.isWeaponItem(itemStack) || ItemUtil.isOtherCheat(itemStack) || ItemUtil.isLegitAxe(itemStack)) {
            return true;
        }
        Item item = itemStack.getItem();
        if (item instanceof ArmorItem armorItem) {
            float candidateScore = ItemUtil.getArmorScore(itemStack);
            float bestScore = ItemUtil.getBestArmorScore(armorItem.getEquipmentSlot());
            return !(candidateScore <= bestScore);
        }
        if (itemStack.getItem() instanceof SwordItem) {
            float candidateDamage = ItemUtil.getSwordDamage(itemStack);
            float bestDamage = ItemUtil.getBestSwordDamage();
            return !(candidateDamage <= bestDamage);
        }
        if (itemStack.getItem() instanceof PickaxeItem) {
            float candidateSpeed = ItemUtil.getDigSpeed(itemStack);
            float bestSpeed = ItemUtil.getBestPickaxeScore();
            return !(candidateSpeed <= bestSpeed);
        }
        if (itemStack.getItem() instanceof AxeItem) {
            float candidateSpeed = ItemUtil.getDigSpeed(itemStack);
            float bestSpeed = ItemUtil.getBestAxeScore();
            return !(candidateSpeed <= bestSpeed);
        }
        if (itemStack.getItem() instanceof ShovelItem) {
            float candidateSpeed = ItemUtil.getDigSpeed(itemStack);
            float bestSpeed = ItemUtil.getBestShovelScore();
            return !(candidateSpeed <= bestSpeed);
        }
        if (itemStack.getItem() instanceof CrossbowItem) {
            float candidateScore = ItemUtil.getCrossbowScore(itemStack);
            float bestScore = ItemUtil.getBestCrossbowScore();
            return !(candidateScore <= bestScore);
        }
        if (itemStack.getItem() instanceof BowItem && ItemUtil.isGoodBow(itemStack)) {
            float candidateScore = ItemUtil.getBowScore(itemStack);
            float bestScore = ItemUtil.getBestBowScore();
            return !(candidateScore <= bestScore);
        }
        if (itemStack.getItem() instanceof BowItem && ItemUtil.isGoodBowAlt(itemStack)) {
            float candidateScore = ItemUtil.getBowScoreAlt(itemStack);
            float bestScore = ItemUtil.getBestBowScoreAlt();
            return !(candidateScore <= bestScore);
        }
        if (itemStack.getItem() == Items.GOLDEN_APPLE || itemStack.getItem() == Items.ENCHANTED_GOLDEN_APPLE) {
            return true;
        }
        if (itemStack.getItem() == Items.COBWEB || itemStack.getItem() == Items.TNT) {
            return true;
        }
        if (itemStack.getItem() == Items.COMPASS) {
            return !ItemUtil.hasItem(itemStack.getItem());
        }
        if (itemStack.getItem() == Items.WATER_BUCKET && ItemUtil.countItem(Items.WATER_BUCKET) >= InventoryManager.getMaxWaterBuckets()) {
            return false;
        }
        if (itemStack.getItem() == Items.LAVA_BUCKET && ItemUtil.countItem(Items.LAVA_BUCKET) >= InventoryManager.getMaxLavaBuckets()) {
            return false;
        }
        if (itemStack.getItem() instanceof BlockItem && BlockUtil.isPlaceable(itemStack) && ItemUtil.countBlocks() + itemStack.getCount() >= InventoryManager.getMaxBlockSize()) {
            return false;
        }
        if (itemStack.getItem() == Items.ARROW && ItemUtil.countItem(Items.ARROW) + itemStack.getCount() >= InventoryManager.getMaxArrows()) {
            return false;
        }
        if (itemStack.getItem() instanceof FishingRodItem && ItemUtil.countItem(Items.FISHING_ROD) >= 1) {
            return false;
        }
        if ((itemStack.getItem() == Items.SNOWBALL || itemStack.getItem() == Items.EGG) && ItemUtil.countItem(Items.SNOWBALL) + ItemUtil.countItem(Items.EGG) + itemStack.getCount() >= InventoryManager.getMaxEggsSnowballsSize()) {
            return false;
        }
        if (itemStack.getItem() instanceof ItemNameBlockItem) {
            return false;
        }
        return ItemUtil.isUsableItem(itemStack);
    }

    static {
        actionTimer = new Timer();
        stealTimer = new Timer();
        openTimer = new Timer();
    }
}