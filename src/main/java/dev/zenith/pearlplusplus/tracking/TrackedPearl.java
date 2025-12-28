package dev.zenith.pearlplusplus.tracking;

import dev.zenith.pearlplusplus.types.BlockPosition;
import dev.zenith.pearlplusplus.types.OwnerInfo;

import java.util.ArrayDeque;
import java.util.Deque;

public final class TrackedPearl {
    private static final long STABLE_LOCATION_DURATION_MS = 3_000L;
    private static final int POSITION_HISTORY_LIMIT = 8;

    private BlockPosition position;
    private final Deque<BlockPosition> history = new ArrayDeque<>();
    private OwnerInfo owner;
    private String pearlId;
    private boolean waitingForNameLogged;
    private long lastMovedAt;
    private boolean conflictNotified;
    private boolean registrationNotified;
    private boolean moved;

    public TrackedPearl(BlockPosition position, OwnerInfo owner, long timestamp) {
        this(position, owner, timestamp, null);
    }

    public TrackedPearl(BlockPosition position, OwnerInfo owner, long timestamp, String pearlId) {
        this.position = position;
        this.owner = owner;
        this.lastMovedAt = timestamp;
        this.pearlId = pearlId;
        history.addLast(position);
    }

    public void updatePosition(BlockPosition newPosition, long timestamp) {
        if (!newPosition.equals(this.position)) {
            this.position = newPosition;
            this.lastMovedAt = timestamp;
            this.moved = true;
            history.addLast(newPosition);
            while (history.size() > POSITION_HISTORY_LIMIT) {
                history.removeFirst();
            }
        }
    }

    public void setOwner(OwnerInfo owner) {
        this.owner = owner;
    }

    public OwnerInfo owner() {
        return owner;
    }

    public boolean ownerHasName() {
        return owner != null && owner.hasName();
    }

    public String ownerSummary() {
        return owner != null ? owner.describe() : "unknown";
    }

    public boolean waitingForNameLogged() {
        return waitingForNameLogged;
    }

    public void markWaitingForNameLogged() {
        this.waitingForNameLogged = true;
    }

    public void clearWaitingForNameLog() {
        this.waitingForNameLogged = false;
    }

    public boolean conflictNotified() {
        return conflictNotified;
    }

    public void markConflictNotified() {
        this.conflictNotified = true;
    }

    boolean registrationNotified() {
        return registrationNotified;
    }

    public void markRegistrationNotified() {
        this.registrationNotified = true;
    }

    boolean hasMoved() {
        return moved;
    }

    public void setPearlId(String pearlId) {
        this.pearlId = pearlId;
    }

    public String pearlId() {
        return pearlId;
    }

    boolean hasPearlId() {
        return pearlId != null && !pearlId.isBlank();
    }

    public boolean isStable(long now) {
        return now - lastMovedAt >= STABLE_LOCATION_DURATION_MS || hasBouncePattern();
    }

    public BlockPosition registrationPosition() {
        if (hasBouncePattern()) {
            return highestRecentBouncePosition();
        }
        return position;
    }

    public int blockX() {
        return position.x();
    }

    public int blockY() {
        return position.y();
    }

    public int blockZ() {
        return position.z();
    }

    private boolean hasBouncePattern() {
        if (history.size() < 5) {
            return false;
        }
        BlockPosition[] points = history.toArray(BlockPosition[]::new);
        BlockPosition latest = points[points.length - 1];
        BlockPosition previous = points[points.length - 2];
        BlockPosition third = points[points.length - 3];
        BlockPosition fourth = points[points.length - 4];
        BlockPosition fifth = points[points.length - 5];

        if (!latest.sameColumn(previous)) {
            return false;
        }
        if (latest.equals(previous)) {
            return false;
        }
        return latest.equals(third) && latest.equals(fifth) && previous.equals(fourth);
    }

    private BlockPosition highestRecentBouncePosition() {
        BlockPosition[] points = history.toArray(BlockPosition[]::new);
        BlockPosition latest = points[points.length - 1];
        BlockPosition previous = points[points.length - 2];
        return latest.y() >= previous.y() ? latest : previous;
    }
}