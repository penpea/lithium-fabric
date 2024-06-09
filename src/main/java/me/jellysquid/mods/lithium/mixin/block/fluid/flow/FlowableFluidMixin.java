package me.jellysquid.mods.lithium.mixin.block.fluid.flow;

import com.google.common.collect.Maps;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.bytes.Byte2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.bytes.Byte2ByteMap;
import it.unimi.dsi.fastutil.bytes.Byte2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import me.jellysquid.mods.lithium.common.util.DirectionConstants;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(FlowableFluid.class)
public abstract class FlowableFluidMixin {

    @Shadow
    public abstract Fluid getFlowing();

    @Shadow
    protected abstract int getFlowSpeed(WorldView world);

    /*
     * getSpread:
     * Given block position and some new block state
     * Caches for block offsets -> block/fluid state and
     *                block offsets -> can flow down bool
     * For each direction:
     *   Cache block offset -> block/fluid state
     *   Get "updated state" for block offset
     */

    @Shadow
    /*Checks if creating a water source or flowing, and the correct block state in case the block is going to be set*/
    protected abstract FluidState getUpdatedState(World world, BlockPos pos, BlockState state);


    @Shadow
    protected abstract boolean canFill(BlockView world, BlockPos pos, BlockState state, Fluid fluid);

    @Shadow
    public abstract Fluid getStill();

    @Shadow
    protected abstract boolean isMatchingAndStill(FluidState state);

    @Shadow
    protected abstract boolean receivesFlow(Direction face, BlockView world, BlockPos pos, BlockState state, BlockPos fromPos, BlockState fromState);


    @Shadow
    protected abstract void flow(WorldAccess world, BlockPos pos, BlockState state, Direction direction, FluidState fluidState);

    @Unique
    private static int getNumIndicesFromRadius(int radius) {
        return (radius + 1) * (2 * radius + 1);
    }

    @Unique
    private static byte indexFromDiamondXZOffset(BlockPos originPos, BlockPos offsetPos, int radius) {
        int xOffset = offsetPos.getX() - originPos.getX();
        int zOffset = offsetPos.getZ() - originPos.getZ();
        
        int row = (xOffset + zOffset + radius) / 2; //Range [0, radius]
        int column = (xOffset - zOffset + radius); //Range [0, 2*radius]
        int rowLength = 2 * radius + 1;
        return (byte) (row * rowLength + column);
    }

    /**
     * @author 2No2Name
     * @reason Faster implementation
     */
    @Inject(method = "getSpread(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Ljava/util/Map;", at = @At("HEAD"), cancellable = true)
    public void getSpread(World world, BlockPos pos, BlockState state, CallbackInfoReturnable<Map<Direction, FluidState>> cir) {
        // check immediate walls if branching is possible (at most 2 walls)
        // if branching is possible, do the complex flow calculations
        // otherwise just handle the single possible direction

        Map<Direction, FluidState> flowResultByDirection = Maps.newEnumMap(Direction.class);
        int searchRadius = this.getFlowSpeed(world) + 1;
        int numIndicesFromRadius = getNumIndicesFromRadius(searchRadius);
        if (numIndicesFromRadius > 256) {
            //We use bytes to represent the indices, which works with vanilla search radius of up to 5
            //Fall back to vanilla code in case the search radius is too large
            return; 
        }
        BlockState[] blockStateCache = new BlockState[numIndicesFromRadius];

        Direction onlyPossibleFlowDirection = null;
        BlockPos onlyBlockPos = null;
        BlockState onlyBlockState = null;
        for(Direction flowDirection : DirectionConstants.HORIZONTAL) {
            BlockPos flowTargetPos = pos.offset(flowDirection);
            byte blockIndex = indexFromDiamondXZOffset(pos, flowTargetPos, searchRadius);
            BlockState flowTargetBlock = world.getBlockState(flowTargetPos);
            blockStateCache[blockIndex] = flowTargetBlock;
            if (this.canMaybeFlowIntoBlock(world, flowTargetBlock)) {
                if (onlyPossibleFlowDirection == null) {
                    onlyPossibleFlowDirection = flowDirection;
                    onlyBlockPos = flowTargetPos;
                    onlyBlockState = flowTargetBlock;
                } else {
                    this.calculateComplexFluidFlowDirections(world, pos, state, blockStateCache, flowResultByDirection);
                    cir.setReturnValue(flowResultByDirection);
                    return;
                }
            }
        }
        if (onlyPossibleFlowDirection != null) {
            FluidState fluidState;
            FluidState targetNewFluidState = this.getUpdatedState(world, onlyBlockPos, onlyBlockState);
            if (this.canFlowThrough(world, targetNewFluidState.getFluid(), pos, state, onlyPossibleFlowDirection, onlyBlockPos, onlyBlockState, onlyBlockState.getFluidState())) {
                fluidState = targetNewFluidState;
                flowResultByDirection.put(onlyPossibleFlowDirection, fluidState);
            }
        }
        cir.setReturnValue(flowResultByDirection);
    }

    /**
     * @author 2No2Name
     * @reason Rearrange to have cheaper checks first
     */
    @Overwrite
    private boolean canFlowThrough(BlockView world, Fluid fluid, BlockPos pos, BlockState state, Direction face, BlockPos fromPos, BlockState fromState, FluidState fluidState) {
        return this.canFill(world, fromPos, fromState, fluid) && !this.isMatchingAndStill(fluidState) && this.receivesFlow(face, world, pos, state, fromPos, fromState);
    }

    /**
     * @author 2No2Name
     * @reason Rearrange to have cheaper checks first
     */
    @Overwrite
    private boolean canFlowDownTo(BlockView world, Fluid fluid, BlockPos pos, BlockState state, BlockPos fromPos, BlockState fromState) {
        return (fromState.getFluidState().getFluid().matchesType((FlowableFluid) (Object) this) || this.canFill(world, fromPos, fromState, fluid)) && this.receivesFlow(Direction.DOWN, world, pos, state, fromPos, fromState);
    }

    @Unique
    private void calculateComplexFluidFlowDirections(World world, BlockPos startPos, BlockState startState, BlockState[] blockStateCache, Map<Direction, FluidState> flowResultByDirection) {
        //Search like breadth-first-search for paths the fluid can flow
        //Only move in directions the fluid can move (e.g. block can contain / be replaced by fluid) (vanilla conditions)
        //For each node remember the first move step (direction) of the paths that led to this node
        //Break when the BFS found all paths of a length up to some length, if any of those paths found a node with a
        // hole below. Then return the union of the stored first move steps of the nodes with a hole below.
        //In total, this finds the directions from the starting location which are the first step towards one of the
        // closest holes, just like vanilla.

        //For each position relevant:
        // Is there a hole below
        // What is the shortest path length to center -
        // Which direct neighbors of the center are on a shortest-path to this location - 4 bits
        // Which direct neighbors of the pos are previous node on the path from center - 4 bits

        Byte2ByteOpenHashMap prevPositions = new Byte2ByteOpenHashMap();
        Byte2ByteOpenHashMap currentPositions = new Byte2ByteOpenHashMap();
        Byte2BooleanOpenHashMap holeCache = new Byte2BooleanOpenHashMap();
        byte holeAccess = 0;
        int searchRadius = this.getFlowSpeed(world) + 1;

        //Like vanilla, the first iteration is separate, because getUpdatedState is called to check whether a
        // renewable fluid source block is created in the flow direction.
        for (int i = 0; i < DirectionConstants.HORIZONTAL.length; i++) {
            Direction flowDirection = DirectionConstants.HORIZONTAL[i];
            BlockPos flowTargetPos = startPos.offset(flowDirection);
            byte blockIndex = indexFromDiamondXZOffset(startPos, flowTargetPos, searchRadius);

            BlockState targetBlockState = getBlock(world, flowTargetPos, blockStateCache, blockIndex);
            //TODO use block cache in getUpdatedState
            FluidState targetNewFluidState = this.getUpdatedState(world, flowTargetPos, targetBlockState);

            //Store the resulting fluid state for each direction, remove later if no closest hole access in this direction.
            flowResultByDirection.put(flowDirection, targetNewFluidState);

            if (this.canFlowThrough(world, targetNewFluidState.getFluid(), startPos, startState, flowDirection, flowTargetPos, targetBlockState, targetBlockState.getFluidState())) {
                prevPositions.put(blockIndex, (byte) (0b10001 << i));
                if (isHoleBelow(world, holeCache, blockIndex, flowTargetPos, targetBlockState)) {
                    holeAccess |= (byte) (1 << i);
                }
            }
        }

        //Iterate over the positions and find the shortest path to the center
        //If a hole is found, stop the iteration
        for (int i = 0; i < this.getFlowSpeed(world) && holeAccess == 0; i++) {
            Fluid targetFluid = this.getFlowing();
            for (ObjectIterator<Byte2ByteMap.Entry> iterator = prevPositions.byte2ByteEntrySet().fastIterator(); iterator.hasNext(); ) {
                Byte2ByteMap.Entry entry = iterator.next();
                byte blockIndex = entry.getByteKey();
                byte currentInfo = entry.getByteValue();

                int rowLength = 2 * searchRadius + 1;
                int row = blockIndex / rowLength;
                int column = blockIndex % rowLength;
                int unevenColumn = column % 2;
                int xOffset = (row * 2 + column + unevenColumn - searchRadius * 2) / 2;
                int zOffset = xOffset - column + searchRadius;

                BlockPos currentPos = startPos.add(xOffset, 0, zOffset);
                BlockState currentState = blockStateCache[blockIndex];

                for (int j = 0; j < DirectionConstants.HORIZONTAL.length; j++) {
                    Direction flowDirection = DirectionConstants.HORIZONTAL[j];
                    int oppositeDirection = DirectionConstants.HORIZONTAL_OPPOSITE_INDICES[j];

                    if (((currentInfo >> 4) & (1 << oppositeDirection)) != (byte) 0) {
                        //In this direction is one of the disallowed directions
                        continue;
                    }
                    BlockPos flowTargetPos = currentPos.offset(flowDirection);
                    byte targetPosBlockIndex = indexFromDiamondXZOffset(startPos, flowTargetPos, searchRadius);
                    if (prevPositions.containsKey(targetPosBlockIndex)) {
                        continue;
                    }

                    byte oldInfo = currentPositions.getOrDefault(targetPosBlockIndex, (byte) 0);
                    byte newInfo = oldInfo;
                    newInfo |= (byte) (0b10000 << j); //Disallow search direction
                    newInfo |= (byte) (currentInfo & 0b1111); //Shortest-reachable with the starting directions
                    if ((newInfo & 0b1111) == (oldInfo & 0b1111)) {
                        currentPositions.put(targetPosBlockIndex, newInfo);
                        continue;
                    }
                    BlockState targetBlockState = getBlock(world, flowTargetPos, blockStateCache, targetPosBlockIndex);
                    if (this.canFlowThrough(world, targetFluid, currentPos, currentState, flowDirection, flowTargetPos, targetBlockState, targetBlockState.getFluidState())) {
                        currentPositions.put(targetPosBlockIndex, newInfo);
                        if (isHoleBelow(world, holeCache, targetPosBlockIndex, flowTargetPos, targetBlockState)) {
                            holeAccess |= (byte) (currentInfo & 0b1111);
                        }
                    }
                }
            }

            var tmp = prevPositions;
            prevPositions = currentPositions;
            currentPositions = tmp;
            currentPositions.clear();
        }

        if (holeAccess != 0) {
            //Found at least one hole in any iteration, keep the directions which lead to the closest holes.
            removeDirectionsWithoutHoleAccess(holeAccess, flowResultByDirection);
        }
    }

    @Unique
    private BlockState getBlock(World world, BlockPos pos, BlockState[] blockStateCache, int key) {
        BlockState blockState = blockStateCache[key];
        if (blockState == null) {
            blockState = world.getBlockState(pos);
            blockStateCache[key] = blockState;
        }
        return blockState;
    }

    @Unique
    private void removeDirectionsWithoutHoleAccess(byte holeAccess, Map<Direction, FluidState> flowResultByDirection) {
        for (int i = 0; i < DirectionConstants.HORIZONTAL.length; i++) {
            if ((holeAccess & (1 << i)) == 0) {
                flowResultByDirection.remove(DirectionConstants.HORIZONTAL[i]);
            }
        }
    }

    /**
     * Fast check to eliminate some choices for the flow direction
     */
    @Unique
    private boolean canMaybeFlowIntoBlock(World world, BlockState blockState) {
        //TODO maybe use this in more places
        //TODO maybe use the blockstate predicate system
        return canFill(world, null, blockState, this.getStill());
    }

    @Unique
    private boolean isHoleBelow(WorldView world, Byte2BooleanOpenHashMap holeCache, byte key, BlockPos flowTargetPos, BlockState targetBlockState) {
        if (holeCache.get(key)) {
            return true;
        }
        BlockPos downPos = flowTargetPos.down();
        BlockState downBlock = world.getBlockState(downPos);
        boolean holeFound = this.canFlowDownTo(world, this.getFlowing(), flowTargetPos, targetBlockState, downPos, downBlock);
        holeCache.put(key, holeFound);
        return holeFound;
    }

    @Redirect(
            method = "canFill",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/block/BlockState;isIn(Lnet/minecraft/registry/tag/TagKey;)Z")
    )
    private boolean isSign(BlockState blockState, TagKey<Block> tagKey, @Local Block block) {
        if (tagKey == BlockTags.SIGNS) {
            //The sign check is expensive when using the block tag lookup.
            return block instanceof AbstractSignBlock;
        }
        return blockState.isIn(tagKey);
    }
}