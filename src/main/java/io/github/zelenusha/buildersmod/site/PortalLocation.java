package io.github.zelenusha.buildersmod.site;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

record PortalLocation(BlockPos lowerLeft, Direction.Axis axis, BlockPos arrival) {
    BlockPos at(int horizontal, int vertical) {
        return lowerLeft.offset(axis == Direction.Axis.X ? horizontal : 0,
                vertical, axis == Direction.Axis.Z ? horizontal : 0);
    }

    boolean containsOpening(BlockPos pos) {
        int horizontal = horizontalOffset(pos);
        int vertical = pos.getY() - lowerLeft.getY();
        return inPlane(pos) && horizontal >= 1 && horizontal <= 2 && vertical >= 1 && vertical <= 3;
    }

    boolean containsFrame(BlockPos pos) {
        int horizontal = horizontalOffset(pos);
        int vertical = pos.getY() - lowerLeft.getY();
        return inPlane(pos) && horizontal >= 0 && horizontal <= 3 && vertical >= 0 && vertical <= 4;
    }

    boolean containsArrival(BlockPos pos) {
        return pos.equals(arrival) || pos.equals(arrival.above());
    }

    boolean protectsFromBreak(BlockPos pos) {
        return pos.equals(arrival.below()) || containsFrame(pos);
    }

    private int horizontalOffset(BlockPos pos) {
        return axis == Direction.Axis.X ? pos.getX() - lowerLeft.getX() : pos.getZ() - lowerLeft.getZ();
    }

    private boolean inPlane(BlockPos pos) {
        return axis == Direction.Axis.X ? pos.getZ() == lowerLeft.getZ() : pos.getX() == lowerLeft.getX();
    }
}
