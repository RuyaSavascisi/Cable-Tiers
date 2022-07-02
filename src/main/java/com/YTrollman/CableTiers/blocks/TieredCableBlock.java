package com.YTrollman.CableTiers.blocks;

import com.YTrollman.CableTiers.CableTier;
import com.YTrollman.CableTiers.ContentType;
import com.YTrollman.CableTiers.node.TieredNetworkNode;
import com.YTrollman.CableTiers.tileentity.TieredTileEntity;
import com.refinedmods.refinedstorage.block.BlockDirection;
import com.refinedmods.refinedstorage.block.CableBlock;
import com.refinedmods.refinedstorage.block.shape.ShapeCache;
import com.refinedmods.refinedstorage.container.factory.PositionalTileContainerProvider;
import com.refinedmods.refinedstorage.render.ConstantsCable;
import com.refinedmods.refinedstorage.util.BlockUtils;
import com.refinedmods.refinedstorage.util.CollisionUtils;
import com.refinedmods.refinedstorage.util.NetworkUtils;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.World;
import net.minecraftforge.fml.network.NetworkHooks;

public abstract class TieredCableBlock<T extends TieredTileEntity<N>, N extends TieredNetworkNode<N>> extends CableBlock {

    private final ContentType<? extends TieredCableBlock<T, N>, T, ?, N> contentType;
    private final CableTier tier;

    protected TieredCableBlock(ContentType<? extends TieredCableBlock<T, N>, T, ?, N> contentType, CableTier tier) {
        super(BlockUtils.DEFAULT_GLASS_PROPERTIES);
        this.contentType = contentType;
        this.tier = tier;
    }

    public ContentType<? extends TieredCableBlock<T, N>, T, ?, N> getContentType() {
        return contentType;
    }

    public CableTier getTier() {
        return tier;
    }

    @Override
    public BlockDirection getDirection() {
        return BlockDirection.ANY;
    }

    protected abstract VoxelShape getHeadShape(BlockState state);

    @Override
    public VoxelShape getShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext ctx) {
        return ConstantsCable.addCoverVoxelShapes(ShapeCache.getOrCreate(state, s -> {
            VoxelShape shape = getCableShape(s);

            shape = VoxelShapes.or(shape, getHeadShape(s));

            return shape;
        }), world, pos);
    }

    @Override
    public TileEntity createTileEntity(BlockState state, IBlockReader world) {
        return contentType.getTileEntityType(tier).create();
    }

    @Override
    public ActionResultType use(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockRayTraceResult hit) {
        if (!world.isClientSide && CollisionUtils.isInBounds(getHeadShape(state), pos, hit.getLocation())) {
            return NetworkUtils.attemptModify(world, pos, player, () -> NetworkHooks.openGui((ServerPlayerEntity) player, new PositionalTileContainerProvider<T>(new TranslationTextComponent(getDescriptionId()), (tile, windowId, inventory, p) -> contentType.createContainer(windowId, p, tile), pos), pos));
        }

        return ActionResultType.SUCCESS;
    }
}
