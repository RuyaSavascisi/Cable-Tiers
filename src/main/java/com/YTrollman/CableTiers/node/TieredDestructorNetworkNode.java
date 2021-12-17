package com.YTrollman.CableTiers.node;

import com.YTrollman.CableTiers.CableTier;
import com.YTrollman.CableTiers.ContentType;
import com.YTrollman.CableTiers.blockentity.TieredDestructorBlockEntity;
import com.YTrollman.CableTiers.config.CableConfig;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.node.ICoverable;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.network.node.cover.CoverManager;
import com.refinedmods.refinedstorage.blockentity.config.IComparable;
import com.refinedmods.refinedstorage.blockentity.config.IType;
import com.refinedmods.refinedstorage.blockentity.config.IWhitelistBlacklist;
import com.refinedmods.refinedstorage.inventory.fluid.FluidInventory;
import com.refinedmods.refinedstorage.inventory.item.BaseItemHandler;
import com.refinedmods.refinedstorage.inventory.item.UpgradeItemHandler;
import com.refinedmods.refinedstorage.inventory.listener.NetworkNodeFluidInventoryListener;
import com.refinedmods.refinedstorage.inventory.listener.NetworkNodeInventoryListener;
import com.refinedmods.refinedstorage.item.UpgradeItem;
import com.refinedmods.refinedstorage.util.StackUtils;
import com.refinedmods.refinedstorage.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.FluidAttributes;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.List;

public class TieredDestructorNetworkNode extends TieredNetworkNode<TieredDestructorNetworkNode> implements IComparable, IWhitelistBlacklist, IType, ICoverable {

    private static final String NBT_COMPARE = "Compare";
    private static final String NBT_MODE = "Mode";
    private static final String NBT_TYPE = "Type";
    private static final String NBT_PICKUP = "Pickup";
    private static final String NBT_FLUID_FILTERS = "FluidFilters";

    private static final int BASE_SPEED = 20;
    private static final int SPEED_INCREASE = 4;

    private final BaseItemHandler itemFilters;
    private final FluidInventory fluidFilters;

    private final UpgradeItemHandler upgrades = (UpgradeItemHandler) new UpgradeItemHandler(
            4,
            new UpgradeItem.Type[] { UpgradeItem.Type.SILK_TOUCH, UpgradeItem.Type.FORTUNE_1, UpgradeItem.Type.FORTUNE_2, UpgradeItem.Type.FORTUNE_3 })
            .addListener(new NetworkNodeInventoryListener(this))
            .addListener((handler, slot, reading) -> tool = createTool());;

    private int compare = IComparer.COMPARE_NBT;
    private int mode = IWhitelistBlacklist.BLACKLIST;
    private int type = IType.ITEMS;
    private boolean pickupItem = false;

    private ItemStack tool = createTool();

    private CoverManager coverManager;

    public TieredDestructorNetworkNode(Level level, BlockPos pos, CableTier tier) {
        super(level, pos, ContentType.DESTRUCTOR, tier);
        this.coverManager = new CoverManager(this);
        this.itemFilters = new BaseItemHandler(9 * tier.getSlotsMultiplier()).addListener(new NetworkNodeInventoryListener(this));
        this.fluidFilters = new FluidInventory(9 * tier.getSlotsMultiplier()).addListener(new NetworkNodeFluidInventoryListener(this));
    }

    @Override
    public int getEnergyUsage() {
        if(getTier() == CableTier.ELITE)
        {
            return (4 * (RS.SERVER_CONFIG.getDestructor().getUsage() + upgrades.getEnergyUsage())) * CableConfig.ELITE_ENERGY_COST.get();
        }
        else if(getTier() == CableTier.ULTRA)
        {
            return (4 * (RS.SERVER_CONFIG.getDestructor().getUsage() + upgrades.getEnergyUsage())) * CableConfig.ULTRA_ENERGY_COST.get();
        }
        else if(getTier() == CableTier.CREATIVE)
        {
            return (4 * (RS.SERVER_CONFIG.getDestructor().getUsage() + upgrades.getEnergyUsage())) * CableConfig.CREATIVE_ENERGY_COST.get();
        }
        return 0;
    }

    @Override
    public void update() {
        super.update();

        if (!canUpdate() || !level.isLoaded(pos) || !level.isLoaded(pos.relative(getDirection()))) {
            return;
        }

        if (getTier() != CableTier.CREATIVE) {
            int baseSpeed = BASE_SPEED / getSpeedMultiplier();
            int speed = Math.max(1, upgrades.getSpeed(baseSpeed, SPEED_INCREASE));
            if (speed > 1 && ticks % speed != 0) {
                return;
            }
        }

        if (type == IType.ITEMS) {
            if (pickupItem) {
                pickupItems();
            } else {
                breakBlock();
            }
        } else if (type == IType.FLUIDS) {
            breakFluid();
        }
    }

    private int getSpeedMultiplier() {
        switch (getTier()) {
            case ELITE:
                return CableConfig.ELITE_DESTRUCTOR_SPEED.get();
            case ULTRA:
                return CableConfig.ULTRA_DESTRUCTOR_SPEED.get();
            default:
                throw new RuntimeException("illegal tier " + getTier());
        }
    }

    private void pickupItems() {
        BlockPos front = pos.relative(getDirection());

        List<ItemEntity> droppedItems = level.getEntitiesOfClass(ItemEntity.class, new AABB(front));

        for (ItemEntity entity : droppedItems) {
            ItemStack droppedItem = ((ItemEntity) entity).getItem();

            if (IWhitelistBlacklist.acceptsItem(itemFilters, mode, compare, droppedItem) &&
                    network.insertItem(droppedItem, droppedItem.getCount(), Action.SIMULATE).isEmpty()) {
                network.insertItemTracked(droppedItem.copy(), droppedItem.getCount());

                entity.remove(Entity.RemovalReason.DISCARDED);

                break;
            }
        }
    }

    private void breakBlock() {
        BlockPos front = pos.relative(getDirection());
        BlockState frontBlockState = level.getBlockState(front);
        Block frontBlock = frontBlockState.getBlock();
        ItemStack frontStack = frontBlock.getCloneItemStack(
                frontBlockState,
                new BlockHitResult(Vec3.ZERO, getDirection().getOpposite(), front, false),
                level,
                front,
                WorldUtils.getFakePlayer((ServerLevel) level, getOwner())
        );

        if (!frontStack.isEmpty() &&
                IWhitelistBlacklist.acceptsItem(itemFilters, mode, compare, frontStack) &&
                frontBlockState.getDestroySpeed(level, front) != -1.0) {
            List<ItemStack> drops = Block.getDrops(
                    frontBlockState,
                    (ServerLevel) level,
                    front,
                    level.getBlockEntity(front),
                    WorldUtils.getFakePlayer((ServerLevel) level, getOwner()),
                    tool
            );

            for (ItemStack drop : drops) {
                if (!network.insertItem(drop, drop.getCount(), Action.SIMULATE).isEmpty()) {
                    return;
                }
            }

            BlockEvent.BreakEvent e = new BlockEvent.BreakEvent(level, front, frontBlockState, WorldUtils.getFakePlayer((ServerLevel) level, getOwner()));

            if (!MinecraftForge.EVENT_BUS.post(e)) {
                frontBlock.playerWillDestroy(level, front, frontBlockState, WorldUtils.getFakePlayer((ServerLevel) level, getOwner()));

                level.removeBlock(front, false);

                for (ItemStack drop : drops) {
                    // We check if the controller isn't null here because when a destructor faces a node and removes it
                    // it will essentially remove this block itself from the network without knowing
                    if (network == null) {
                        Containers.dropItemStack(level, front.getX(), front.getY(), front.getZ(), drop);
                    } else {
                        network.insertItemTracked(drop, drop.getCount());
                    }
                }
            }
        }
    }

    private void breakFluid() {
        BlockPos front = pos.relative(getDirection());
        BlockState frontBlockState = level.getBlockState(front);
        Block frontBlock = frontBlockState.getBlock();

        if (frontBlock instanceof LiquidBlock) {
            // @Volatile: Logic from FlowingFluidBlock#pickupFluid
            if (frontBlockState.getValue(LiquidBlock.LEVEL) == 0) {
                Fluid fluid = ((LiquidBlock) frontBlock).getFluid();

                FluidStack stack = new FluidStack(fluid, FluidAttributes.BUCKET_VOLUME);

                if (IWhitelistBlacklist.acceptsFluid(fluidFilters, mode, compare, stack) &&
                        network.insertFluid(stack, stack.getAmount(), Action.SIMULATE).isEmpty()) {
                    network.insertFluidTracked(stack, stack.getAmount());

                    level.setBlock(front, Blocks.AIR.defaultBlockState(), 11);
                }
            }
        } else if (frontBlock instanceof IFluidBlock) {
            IFluidBlock fluidBlock = (IFluidBlock) frontBlock;

            if (fluidBlock.canDrain(level, front)) {
                FluidStack simulatedDrain = fluidBlock.drain(level, front, IFluidHandler.FluidAction.SIMULATE);

                if (IWhitelistBlacklist.acceptsFluid(fluidFilters, mode, compare, simulatedDrain) &&
                        network.insertFluid(simulatedDrain, simulatedDrain.getAmount(), Action.SIMULATE).isEmpty()) {
                    FluidStack drained = fluidBlock.drain(level, front, IFluidHandler.FluidAction.EXECUTE);

                    network.insertFluidTracked(drained, drained.getAmount());
                }
            }
        }
    }

    private ItemStack createTool() {
        ItemStack newTool = new ItemStack(getTier() == CableTier.CREATIVE ? Items.NETHERITE_PICKAXE : Items.DIAMOND_PICKAXE);

        if (upgrades.hasUpgrade(UpgradeItem.Type.SILK_TOUCH)) {
            newTool.enchant(Enchantments.SILK_TOUCH, 1);
        } else if (upgrades.hasUpgrade(UpgradeItem.Type.FORTUNE_3)) {
            newTool.enchant(Enchantments.BLOCK_FORTUNE, 3);
        } else if (upgrades.hasUpgrade(UpgradeItem.Type.FORTUNE_2)) {
            newTool.enchant(Enchantments.BLOCK_FORTUNE, 2);
        } else if (upgrades.hasUpgrade(UpgradeItem.Type.FORTUNE_1)) {
            newTool.enchant(Enchantments.BLOCK_FORTUNE, 1);
        }

        return newTool;
    }

    @Override
    public int getCompare() {
        return compare;
    }

    @Override
    public void setCompare(int compare) {
        this.compare = compare;
        markDirty();
    }

    @Override
    public int getWhitelistBlacklistMode() {
        return mode;
    }

    @Override
    public void setWhitelistBlacklistMode(int mode) {
        this.mode = mode;
        markDirty();
    }

    @Override
    public void read(CompoundTag tag) {
        super.read(tag);
        if (tag.contains(CoverManager.NBT_COVER_MANAGER)){
            this.coverManager.readFromNbt(tag.getCompound(CoverManager.NBT_COVER_MANAGER));
        }
        StackUtils.readItems(upgrades, 1, tag);
    }

    @Override
    public CompoundTag write(CompoundTag tag) {
        super.write(tag);
        tag.put(CoverManager.NBT_COVER_MANAGER, this.coverManager.writeToNbt());
        StackUtils.writeItems(upgrades, 1, tag);
        return tag;
    }

    @Override
    public CompoundTag writeConfiguration(CompoundTag tag) {
        super.writeConfiguration(tag);
        tag.putInt(NBT_COMPARE, compare);
        tag.putInt(NBT_MODE, mode);
        tag.putInt(NBT_TYPE, type);
        tag.putBoolean(NBT_PICKUP, pickupItem);
        StackUtils.writeItems(itemFilters, 0, tag);
        tag.put(NBT_FLUID_FILTERS, fluidFilters.writeToNbt());
        return tag;
    }

    @Override
    public void readConfiguration(CompoundTag tag) {
        super.readConfiguration(tag);
        if (tag.contains(NBT_COMPARE)) {
            compare = tag.getInt(NBT_COMPARE);
        }
        if (tag.contains(NBT_MODE)) {
            mode = tag.getInt(NBT_MODE);
        }
        if (tag.contains(NBT_TYPE)) {
            type = tag.getInt(NBT_TYPE);
        }
        if (tag.contains(NBT_PICKUP)) {
            pickupItem = tag.getBoolean(NBT_PICKUP);
        }
        StackUtils.readItems(itemFilters, 0, tag);
        if (tag.contains(NBT_FLUID_FILTERS)) {
            fluidFilters.readFromNbt(tag.getCompound(NBT_FLUID_FILTERS));
        }
    }

    public IItemHandler getUpgrades() {
        return upgrades;
    }

    @Override
    public IItemHandler getDrops() {
        return getUpgrades();
    }

    @Override
    public int getType() {
        return level.isClientSide ? TieredDestructorBlockEntity.TYPE.getValue() : type;
    }

    @Override
    public void setType(int type) {
        this.type = type;
        markDirty();
    }

    @Override
    public IItemHandlerModifiable getItemFilters() {
        return itemFilters;
    }

    @Override
    public FluidInventory getFluidFilters() {
        return fluidFilters;
    }

    public boolean isPickupItem() {
        return pickupItem;
    }

    public void setPickupItem(boolean pickupItem) {
        this.pickupItem = pickupItem;
    }

    @Override
    public CoverManager getCoverManager() {
        return coverManager;
    }
}
