package com.YTrollman.CableTiers.blocks;

import com.YTrollman.CableTiers.CableTier;
import com.YTrollman.CableTiers.ContentType;
import com.YTrollman.CableTiers.node.TieredNetworkNode;
import com.YTrollman.CableTiers.blockentity.TieredBlockEntity;
import com.refinedmods.refinedstorage.block.BlockDirection;
import com.refinedmods.refinedstorage.block.CableBlock;
import com.refinedmods.refinedstorage.block.shape.ShapeCache;
import com.refinedmods.refinedstorage.container.factory.BlockEntityMenuProvider;
import com.refinedmods.refinedstorage.render.ConstantsCable;
import com.refinedmods.refinedstorage.util.BlockUtils;
import com.refinedmods.refinedstorage.util.CollisionUtils;
import com.refinedmods.refinedstorage.util.NetworkUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;

public abstract class TieredCableBlock<T extends TieredBlockEntity<N>, N extends TieredNetworkNode<N>> extends CableBlock {

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
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx) {
        return ConstantsCable.addCoverVoxelShapes(ShapeCache.getOrCreate(state, s -> {
            VoxelShape shape = getCableShape(s);

            shape = Shapes.or(shape, getHeadShape(s));

            return shape;
        }), world, pos);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return contentType.getBlockEntityType(tier).create(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && CollisionUtils.isInBounds(getHeadShape(state), pos, hit.getLocation())) {
            return NetworkUtils.attemptModify(
                    level,
                    pos,
                    player,
                    () -> NetworkHooks.openGui(
                            (ServerPlayer) player,
                            new BlockEntityMenuProvider<T>(
                                    new TranslatableComponent(getDescriptionId()),
                                    (tile, windowId, inventory, p) -> contentType.createContainer(windowId, p, tile),
                                    pos
                            ),
                            pos
                    )
            );
        }

        return InteractionResult .SUCCESS;
    }
}
