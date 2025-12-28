package dev.zenith.pearlplusplus.types;

public record Column(int x, int z) {
    public Column(BlockPosition pos) {
        this(pos.x(), pos.z());
    }

    //    public static Column columnOf(BlockPosition position) {
//        if (position == null) {
//            return null;
//        }
//        return new Column(position.x(), position.z());
//    }
}