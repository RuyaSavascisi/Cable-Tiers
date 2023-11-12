package com.ultramega.cabletiers.node;

import com.ultramega.cabletiers.CableTier;
import com.ultramega.cabletiers.ContentType;
import com.ultramega.cabletiers.blockentity.TieredExporterBlockEntity;
import com.ultramega.cabletiers.config.CableConfig;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.node.ICoverable;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.network.node.SlottedCraftingRequest;
import com.refinedmods.refinedstorage.apiimpl.network.node.cover.CoverManager;
import com.refinedmods.refinedstorage.blockentity.config.IComparable;
import com.refinedmods.refinedstorage.blockentity.config.IType;
import com.refinedmods.refinedstorage.inventory.fluid.FluidInventory;
import com.refinedmods.refinedstorage.inventory.item.BaseItemHandler;
import com.refinedmods.refinedstorage.inventory.item.UpgradeItemHandler;
import com.refinedmods.refinedstorage.inventory.listener.NetworkNodeFluidInventoryListener;
import com.refinedmods.refinedstorage.inventory.listener.NetworkNodeInventoryListener;
import com.refinedmods.refinedstorage.item.UpgradeItem;
import com.refinedmods.refinedstorage.util.LevelUtils;
import com.refinedmods.refinedstorage.util.StackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;

public class TieredExporterNetworkNode extends TieredNetworkNode<TieredExporterNetworkNode> implements IComparable, IType, ICoverable {
    private static final String NBT_COMPARE = "Compare";
    private static final String NBT_TYPE = "Type";
    private static final String NBT_FLUID_FILTERS = "FluidFilters";

    private static final int BASE_SPEED = 9;
    private static final int SPEED_INCREASE = 2;

    private final BaseItemHandler itemFilters;
    private final FluidInventory fluidFilters;

    private final UpgradeItemHandler upgrades;

    private int compare = IComparer.COMPARE_NBT;
    private int type = IType.ITEMS;

    private int filterSlot;

    private final CoverManager coverManager;

    public TieredExporterNetworkNode(Level level, BlockPos pos, CableTier tier) {
        super(level, pos, ContentType.EXPORTER, tier);
        this.coverManager = new CoverManager(this);
        this.itemFilters  = new BaseItemHandler(9 * tier.getSlotsMultiplier()).addListener(new NetworkNodeInventoryListener(this));
        this.fluidFilters = new FluidInventory(9 * tier.getSlotsMultiplier()).addListener(new NetworkNodeFluidInventoryListener(this));
        this.upgrades     = (UpgradeItemHandler) new UpgradeItemHandler(4, checkTierUpgrade()).addListener(new NetworkNodeInventoryListener(this)).addListener((handler, slot, reading) -> {
            if (!reading && !getUpgrades().hasUpgrade(UpgradeItem.Type.REGULATOR)) {
                boolean changed = false;

                for (int i = 0; i < itemFilters.getSlots(); i++) {
                    ItemStack filteredItem = itemFilters.getStackInSlot(i);

                    if (filteredItem.getCount() > 1) {
                        filteredItem.setCount(1);
                        changed = true;
                    }
                }

                for (int i = 0; i < fluidFilters.getSlots(); i++) {
                    FluidStack filteredFluid = fluidFilters.getFluid(i);

                    if (!filteredFluid.isEmpty() && filteredFluid.getAmount() != FluidType.BUCKET_VOLUME) {
                        filteredFluid.setAmount(FluidType.BUCKET_VOLUME);
                        changed = true;
                    }
                }

                if (changed) {
                    markDirty();
                }
            }
        });
    }

    private UpgradeItem.Type[] checkTierUpgrade() {
        if (getTier() == CableTier.ELITE) {
            return new UpgradeItem.Type[]{UpgradeItem.Type.SPEED, UpgradeItem.Type.STACK, UpgradeItem.Type.CRAFTING, UpgradeItem.Type.REGULATOR};
        } else if (getTier() == CableTier.ULTRA) {
            return new UpgradeItem.Type[]{UpgradeItem.Type.SPEED, UpgradeItem.Type.CRAFTING, UpgradeItem.Type.REGULATOR};
        } else if (getTier() == CableTier.CREATIVE) {
            return new UpgradeItem.Type[]{UpgradeItem.Type.CRAFTING, UpgradeItem.Type.REGULATOR};
        }
        return null;
    }

    @Override
    public int getEnergyUsage() {
        if (getTier() == CableTier.ELITE) {
            return (4 * (RS.SERVER_CONFIG.getExporter().getUsage() + upgrades.getEnergyUsage())) * CableConfig.ELITE_ENERGY_COST.get();
        } else if (getTier() == CableTier.ULTRA) {
            return (4 * (RS.SERVER_CONFIG.getExporter().getUsage() + upgrades.getEnergyUsage())) * CableConfig.ULTRA_ENERGY_COST.get();
        } else if (getTier() == CableTier.CREATIVE) {
            return (4 * (RS.SERVER_CONFIG.getExporter().getUsage() + upgrades.getEnergyUsage())) * CableConfig.CREATIVE_ENERGY_COST.get();
        }
        return 0;
    }

    private int getSpeedMultiplier() {
        switch (getTier()) {
            case ELITE:
                return CableConfig.ELITE_EXPORTER_SPEED.get();
            case ULTRA:
                return CableConfig.ULTRA_EXPORTER_SPEED.get();
            default:
                throw new RuntimeException("illegal tier " + getTier());
        }
    }

    private boolean interactWithStacks() {
        return getTier() != CableTier.ELITE || upgrades.hasUpgrade(UpgradeItem.Type.STACK);
    }

    private int getInteractionSize(ItemStack stack) {
        return interactWithStacks() ? stack.getMaxStackSize() : 1;
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
            itemUpdate();
        } else if (type == IType.FLUIDS) {
            fluidUpdate();
        }
    }

    private void itemUpdate() {
        IItemHandler handler = LevelUtils.getItemHandler(getFacingBlockEntity(), getDirection().getOpposite());
        if (handler == null || handler.getSlots() <= 0) {
            return;
        }

        if (filterSlot >= itemFilters.getSlots()) {
            filterSlot = 0;
        }

        if (getTier() == CableTier.CREATIVE) {
            while (doItemInsertion(handler)) {
            }
        } else {
            doItemInsertion(handler);
        }
    }

    private boolean doItemInsertion(IItemHandler handler) {
        int startSlot = filterSlot;

        while (true) {
            boolean work = false;
            ItemStack filter = itemFilters.getStackInSlot(filterSlot);
            if (!filter.isEmpty()) {
                int interactionCount = interactWithStacks() ? filter.getMaxStackSize() : 1;

                int toTransfer;
                if (upgrades.hasUpgrade(UpgradeItem.Type.REGULATOR)) {
                    int requested = 0;
                    for (int i = 0; i < itemFilters.getSlots(); i++) {
                        ItemStack otherFilter = itemFilters.getStackInSlot(i);
                        if (API.instance().getComparer().isEqual(filter, otherFilter, compare)) {
                            requested += otherFilter.getCount();
                        }
                    }

                    int actual = 0;
                    for (int i = 0; i < handler.getSlots(); i++) {
                        ItemStack stack = handler.getStackInSlot(i);
                        if (API.instance().getComparer().isEqual(filter, stack, compare)) {
                            actual += stack.getCount();
                        }
                    }

                    int needed = Math.max(0, requested - actual);
                    toTransfer = Math.min(interactionCount, needed);
                } else {
                    toTransfer = interactionCount;
                }

                if (toTransfer > 0) {
                    ItemStack extracted = network.extractItem(filter, toTransfer, compare, Action.SIMULATE);
                    if (extracted.isEmpty()) {
                        if (upgrades.hasUpgrade(UpgradeItem.Type.CRAFTING)) {
                            network.getCraftingManager().request(new SlottedCraftingRequest(this, filterSlot), filter, toTransfer);
                            work = false;
                        }
                    } else {
                        if (getTier() == CableTier.ELITE) {
                            int remaining = ItemHandlerHelper.insertItem(handler, extracted, true).getCount();
                            int inserted = extracted.getCount() - remaining;
                            if (inserted > 0) {
                                extracted = network.extractItem(filter, inserted, compare, Action.PERFORM);
                                ItemHandlerHelper.insertItem(handler, extracted, false);

                                work = true;
                            }
                        } else {
                            int remaining = ItemHandlerHelper.insertItemStacked(handler, extracted, true).getCount();
                            int inserted = extracted.getCount() - remaining;
                            if (inserted > 0) {
                                extracted = network.extractItem(filter, inserted, compare, Action.PERFORM);
                                ItemHandlerHelper.insertItemStacked(handler, extracted, false);

                                work = true;
                            }
                        }
                    }
                }
            }

            if (++filterSlot >= itemFilters.getSlots()) {
                filterSlot = 0;
            }

            if (work) {
                return true;
            }

            if (filterSlot == startSlot) {
                return false;
            }
        }
    }

    private void fluidUpdate() {
        IFluidHandler handler = LevelUtils.getFluidHandler(getFacingBlockEntity(), getDirection().getOpposite());
        if (handler == null || handler.getTanks() <= 0) {
            return;
        }

        if (filterSlot >= fluidFilters.getSlots()) {
            filterSlot = 0;
        }


        if (getTier() == CableTier.CREATIVE) {
            while (doFluidInsertion(handler)) {
            }
        } else {
            doFluidInsertion(handler);
        }
    }

    private boolean doFluidInsertion(IFluidHandler handler) {
        int startSlot = filterSlot;

        while (true) {
            boolean work = false;
            FluidStack filter = fluidFilters.getFluid(filterSlot);
            if (!filter.isEmpty()) {
                int interactionCount = interactWithStacks() ? (getTier() == CableTier.CREATIVE ? Integer.MAX_VALUE : 64 * FluidType.BUCKET_VOLUME) : FluidType.BUCKET_VOLUME;

                int toTransfer;
                if (upgrades.hasUpgrade(UpgradeItem.Type.REGULATOR)) {
                    int requested = 0;
                    for (int i = 0; i < fluidFilters.getSlots(); i++) {
                        FluidStack otherFilter = fluidFilters.getFluid(i);
                        if (API.instance().getComparer().isEqual(filter, otherFilter, compare)) {
                            requested += otherFilter.getAmount();
                        }
                    }

                    int actual = 0;
                    for (int i = 0; i < handler.getTanks(); i++) {
                        FluidStack stack = handler.getFluidInTank(i);
                        if (API.instance().getComparer().isEqual(filter, stack, compare)) {
                            actual += stack.getAmount();
                        }
                    }

                    int needed = Math.max(0, requested - actual);
                    toTransfer = Math.min(interactionCount, needed);
                } else {
                    toTransfer = interactionCount;
                }

                if (toTransfer > 0) {
                    FluidStack extracted = network.extractFluid(filter, toTransfer, compare, Action.SIMULATE);
                    if (extracted.isEmpty()) {
                        if (upgrades.hasUpgrade(UpgradeItem.Type.CRAFTING) && !filter.isEmpty()) {
                            network.getCraftingManager().request(new SlottedCraftingRequest(this, filterSlot), filter, toTransfer);
                            work = false;
                        }
                    } else {
                        int inserted = handler.fill(extracted, IFluidHandler.FluidAction.SIMULATE);
                        if (inserted > 0) {
                            extracted = network.extractFluid(filter, inserted, compare, Action.PERFORM);
                            handler.fill(extracted, IFluidHandler.FluidAction.EXECUTE);

                            work = true;
                        }
                    }

                }
            }

            if (++filterSlot >= fluidFilters.getSlots()) {
                filterSlot = 0;
            }

            if (work) {
                return true;
            }

            if (filterSlot == startSlot) {
                return false;
            }
        }
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
        tag.putInt(NBT_TYPE, type);
        StackUtils.writeItems(itemFilters, 0, tag);
        tag.put(NBT_FLUID_FILTERS, fluidFilters.writeToNbt());
        return tag;
    }

    @Override
    public void read(CompoundTag tag) {
        super.read(tag);
        if (tag.contains(CoverManager.NBT_COVER_MANAGER)) {
            this.coverManager.readFromNbt(tag.getCompound(CoverManager.NBT_COVER_MANAGER));
        }
        StackUtils.readItems(upgrades, 1, tag);
    }

    @Override
    public void readConfiguration(CompoundTag tag) {
        super.readConfiguration(tag);
        if (tag.contains(NBT_COMPARE)) {
            compare = tag.getInt(NBT_COMPARE);
        }
        if (tag.contains(NBT_TYPE)) {
            type = tag.getInt(NBT_TYPE);
        }
        StackUtils.readItems(itemFilters, 0, tag);
        if (tag.contains(NBT_FLUID_FILTERS)) {
            fluidFilters.readFromNbt(tag.getCompound(NBT_FLUID_FILTERS));
        }
    }

    public UpgradeItemHandler getUpgrades() {
        return upgrades;
    }

    @Override
    public IItemHandler getDrops() {
        return getUpgrades();
    }

    @Override
    public int getType() {
        return level.isClientSide ? TieredExporterBlockEntity.TYPE.getValue() : type;
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

    @Override
    public CoverManager getCoverManager() {
        return coverManager;
    }
}
