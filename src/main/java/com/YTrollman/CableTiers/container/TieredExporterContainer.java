package com.YTrollman.CableTiers.container;

import com.YTrollman.CableTiers.ContentType;
import com.YTrollman.CableTiers.node.TieredExporterNetworkNode;
import com.YTrollman.CableTiers.tileentity.TieredExporterTileEntity;
import com.YTrollman.CableTiers.util.MathUtil;
import com.refinedmods.refinedstorage.container.slot.filter.FilterSlot;
import com.refinedmods.refinedstorage.container.slot.filter.FluidFilterSlot;
import com.refinedmods.refinedstorage.item.UpgradeItem;
import net.minecraft.entity.player.PlayerEntity;

public class TieredExporterContainer extends TieredContainer<TieredExporterTileEntity, TieredExporterNetworkNode> {
    private boolean hasRegulatorMode;

    public TieredExporterContainer(int windowId, PlayerEntity player, TieredExporterTileEntity tile) {
        super(ContentType.EXPORTER, tile, player, windowId);
        this.hasRegulatorMode = hasRegulatorMode();
        initSlots();
    }

    private boolean hasRegulatorMode() {
        return getNode().getUpgrades().hasUpgrade(UpgradeItem.Type.REGULATOR);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        checkRegulator();
    }

    public void checkRegulator() {
        boolean updatedHasRegulatorMode = hasRegulatorMode();
        if (hasRegulatorMode != updatedHasRegulatorMode) {
            hasRegulatorMode = updatedHasRegulatorMode;
            slots.clear();
            lastSlots.clear();
            transferManager.clearTransfers();
            initSlots();
        }
    }

    private void initSlots() {
        addUpgradeSlots(getNode().getUpgrades());
        addFilterSlots(getNode().getItemFilters(), hasRegulatorMode ? FilterSlot.FILTER_ALLOW_SIZE : 0, getNode().getFluidFilters(), hasRegulatorMode ? FluidFilterSlot.FILTER_ALLOW_SIZE : 0, getNode());
        addPlayerInventory(8, 37 + 18 * MathUtil.ceilDiv(9 * getTier().getSlotsMultiplier(), 9));

        transferManager.addBiTransfer(getPlayer().inventory, getNode().getUpgrades());
        transferManager.addFilterTransfer(getPlayer().inventory, getNode().getItemFilters(), getNode().getFluidFilters(), getNode()::getType);
    }
}
