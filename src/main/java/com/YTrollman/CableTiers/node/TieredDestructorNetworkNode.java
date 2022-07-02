package com.YTrollman.CableTiers.node;

import com.YTrollman.CableTiers.CableTier;
import com.YTrollman.CableTiers.ContentType;
import com.YTrollman.CableTiers.config.CableConfig;
import com.YTrollman.CableTiers.tileentity.TieredDestructorTileEntity;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.node.ICoverable;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.network.node.cover.CoverManager;
import com.refinedmods.refinedstorage.inventory.fluid.FluidInventory;
import com.refinedmods.refinedstorage.inventory.item.BaseItemHandler;
import com.refinedmods.refinedstorage.inventory.item.UpgradeItemHandler;
import com.refinedmods.refinedstorage.inventory.listener.NetworkNodeFluidInventoryListener;
import com.refinedmods.refinedstorage.inventory.listener.NetworkNodeInventoryListener;
import com.refinedmods.refinedstorage.item.UpgradeItem;
import com.refinedmods.refinedstorage.tile.config.IComparable;
import com.refinedmods.refinedstorage.tile.config.IType;
import com.refinedmods.refinedstorage.tile.config.IWhitelistBlacklist;
import com.refinedmods.refinedstorage.util.StackUtils;
import com.refinedmods.refinedstorage.util.WorldUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.IBucketPickupHandler;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.EntityPredicates;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.FluidAttributes;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;

public class TieredDestructorNetworkNode extends TieredNetworkNode<TieredDestructorNetworkNode> implements IComparable, IWhitelistBlacklist, IType, ICoverable {
    private static final String NBT_COMPARE = "Compare";
    private static final String NBT_MODE = "Mode";
    private static final String NBT_TYPE = "Type";
    private static final String NBT_PICKUP = "Pickup";
    private static final String NBT_FLUID_FILTERS = "FluidFilters";

    private static final int BASE_SPEED = 20;
    private static final int SPEED_INCREASE = 4;

    private final BaseItemHandler itemFilters = new BaseItemHandler(9 * getTier().getSlotsMultiplier()).addListener(new NetworkNodeInventoryListener(this));
    private final FluidInventory fluidFilters = new FluidInventory(9 * getTier().getSlotsMultiplier()).addListener(new NetworkNodeFluidInventoryListener(this));

    private final UpgradeItemHandler upgrades = (UpgradeItemHandler) new UpgradeItemHandler(4, checkTierUpgrades()).addListener(new NetworkNodeInventoryListener(this)).addListener((handler, slot, reading) -> tool = createTool());

    private int compare = IComparer.COMPARE_NBT;
    private int mode = IWhitelistBlacklist.BLACKLIST;
    private int type = IType.ITEMS;
    private boolean pickupItem = false;

    private ItemStack tool = createTool();

    private CoverManager coverManager;

    public TieredDestructorNetworkNode(World world, BlockPos pos, CableTier tier) {
        super(world, pos, ContentType.DESTRUCTOR, tier);
        this.coverManager = new CoverManager(this);
    }

    private UpgradeItem.Type[] checkTierUpgrades() {
        if (getTier() == CableTier.CREATIVE) {
            return new UpgradeItem.Type[]{UpgradeItem.Type.SILK_TOUCH, UpgradeItem.Type.FORTUNE_1, UpgradeItem.Type.FORTUNE_2, UpgradeItem.Type.FORTUNE_3};
        } else {
            return new UpgradeItem.Type[]{UpgradeItem.Type.SPEED, UpgradeItem.Type.SILK_TOUCH, UpgradeItem.Type.FORTUNE_1, UpgradeItem.Type.FORTUNE_2, UpgradeItem.Type.FORTUNE_3};
        }
    }

    @Override
    public int getEnergyUsage() {
        if (getTier() == CableTier.ELITE) {
            return (4 * (RS.SERVER_CONFIG.getDestructor().getUsage() + upgrades.getEnergyUsage())) * CableConfig.ELITE_ENERGY_COST.get();
        } else if (getTier() == CableTier.ULTRA) {
            return (4 * (RS.SERVER_CONFIG.getDestructor().getUsage() + upgrades.getEnergyUsage())) * CableConfig.ULTRA_ENERGY_COST.get();
        } else if (getTier() == CableTier.CREATIVE) {
            return (4 * (RS.SERVER_CONFIG.getDestructor().getUsage() + upgrades.getEnergyUsage())) * CableConfig.CREATIVE_ENERGY_COST.get();
        }
        return 0;
    }

    @Override
    public void update() {
        super.update();

        if (!canUpdate() || !world.isLoaded(pos) || !world.isLoaded(pos.relative(getDirection()))) {
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
        List<ItemEntity> droppedItems = new ArrayList<>();
        world.getChunkAt(front).getEntitiesOfClass(ItemEntity.class, new AxisAlignedBB(front), droppedItems, EntityPredicates.ENTITY_STILL_ALIVE);
        for (ItemEntity entity : droppedItems) {
            ItemStack droppedItem = entity.getItem();
            if (droppedItem.isEmpty() || !IWhitelistBlacklist.acceptsItem(itemFilters, mode, compare, droppedItem)) {
                continue;
            }

            ItemStack remaining = network.insertItemTracked(droppedItem.copy(), droppedItem.getCount());
            int inserted = droppedItem.getCount() - remaining.getCount();
            if (inserted > 0) {
                if (remaining.isEmpty()) {
                    entity.remove();
                } else {
                    entity.setItem(remaining);
                }

                if (getTier() != CableTier.CREATIVE) {
                    break;
                }
            }
        }
    }

    private void breakBlock() {
        BlockPos front = pos.relative(getDirection());
        BlockState frontBlockState = world.getBlockState(front);
        if (frontBlockState.getDestroySpeed(world, front) < 0) {
            return;
        }

        Block frontBlock = frontBlockState.getBlock();
        FakePlayer fakePlayer = WorldUtils.getFakePlayer((ServerWorld) world, getOwner());
        ItemStack frontStack = frontBlock.getPickBlock(frontBlockState, new BlockRayTraceResult(Vector3d.ZERO, getDirection().getOpposite(), front, false), world, front, fakePlayer);
        if (frontStack.isEmpty() || !IWhitelistBlacklist.acceptsItem(itemFilters, mode, compare, frontStack)) {
            return;
        }

        List<ItemStack> drops = Block.getDrops(frontBlockState, (ServerWorld) world, front, world.getBlockEntity(front), fakePlayer, tool);

        for (ItemStack drop : drops) {
            if (!network.insertItem(drop, drop.getCount(), Action.SIMULATE).isEmpty()) {
                return;
            }
        }

        BlockEvent.BreakEvent e = new BlockEvent.BreakEvent(world, front, frontBlockState, fakePlayer);
        if (!MinecraftForge.EVENT_BUS.post(e)) {
            frontBlock.playerWillDestroy(world, front, frontBlockState, fakePlayer);

            world.removeBlock(front, false);

            for (ItemStack drop : drops) {
                // We check if the controller isn't null here because when a destructor faces a node and removes it
                // it will essentially remove this block itself from the network without knowing
                if (network == null) {
                    InventoryHelper.dropItemStack(world, front.getX() + 0.5, front.getY() + 0.5, front.getZ() + 0.5, drop);
                } else {
                    ItemStack remainder = network.insertItemTracked(drop, drop.getCount());

                }
            }
        }
    }

    private void breakFluid() {
        BlockPos front = pos.relative(getDirection());
        BlockState frontBlockState = world.getBlockState(front);
        Block frontBlock = frontBlockState.getBlock();

        if (frontBlock instanceof IFluidBlock) {
            IFluidBlock fluidBlock = (IFluidBlock) frontBlock;
            if (fluidBlock.canDrain(world, front)) {
                FluidStack result = fluidBlock.drain(world, front, IFluidHandler.FluidAction.SIMULATE);
                if (!result.isEmpty() && IWhitelistBlacklist.acceptsFluid(fluidFilters, mode, compare, result) && network.insertFluid(result, result.getAmount(), Action.SIMULATE).isEmpty()) {
                    result = fluidBlock.drain(world, front, IFluidHandler.FluidAction.EXECUTE);

                    FluidStack remainder = network.insertFluidTracked(result, result.getAmount());

                    return;
                }
            }
        }

        if (frontBlock instanceof IBucketPickupHandler) {
            IBucketPickupHandler bucketPickupHandler = (IBucketPickupHandler) frontBlock;
            FluidState fluidState = world.getFluidState(front);
            if (!fluidState.isEmpty()) {
                FluidStack result = new FluidStack(fluidState.getType(), FluidAttributes.BUCKET_VOLUME);
                if (!result.isEmpty() && IWhitelistBlacklist.acceptsFluid(fluidFilters, mode, compare, result) && network.insertFluid(result, result.getAmount(), Action.SIMULATE).isEmpty()) {
                    result = new FluidStack(bucketPickupHandler.takeLiquid(world, front, frontBlockState), FluidAttributes.BUCKET_VOLUME);
                    FluidStack remainder = network.insertFluidTracked(result, result.getAmount());

                    return;
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
    public void read(CompoundNBT tag) {
        super.read(tag);
        if (tag.contains(CoverManager.NBT_COVER_MANAGER)) {
            this.coverManager.readFromNbt(tag.getCompound(CoverManager.NBT_COVER_MANAGER));
        }
        StackUtils.readItems(upgrades, 1, tag);
    }

    @Override
    public CompoundNBT write(CompoundNBT tag) {
        super.write(tag);
        tag.put(CoverManager.NBT_COVER_MANAGER, this.coverManager.writeToNbt());
        StackUtils.writeItems(upgrades, 1, tag);
        return tag;
    }

    @Override
    public CompoundNBT writeConfiguration(CompoundNBT tag) {
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
    public void readConfiguration(CompoundNBT tag) {
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
        return world.isClientSide ? TieredDestructorTileEntity.TYPE.getValue() : type;
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
