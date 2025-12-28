package dev.zenith.pearlplusplus.types;


import com.zenith.cache.data.entity.Entity;

public record BlockPosition(int x, int y, int z) {
    public boolean sameColumn(BlockPosition other) {
        return other != null && this.x == other.x && this.z == other.z;
    }

    public static BlockPosition blockPositionOf(Entity entity) {
        return new BlockPosition(
                (int) Math.floor(entity.getX()),
                (int) Math.floor(entity.getY()),
                (int) Math.floor(entity.getZ())
        );
    }
}