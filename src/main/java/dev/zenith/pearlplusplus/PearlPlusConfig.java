package dev.zenith.pearlplusplus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PearlPlusConfig {
    public final AutoLoadConfig autoLoad = new AutoLoadConfig();
    public final AutoDetectConfig autoDetect = new AutoDetectConfig();

    public String defaultPearlId = "Base";

    public final Map<UUID, PlayerPearls> players = new LinkedHashMap<>();

    public static class AutoLoadConfig {
        public boolean enabled = true;
        public boolean allowNoiseAfterPearl = true;
        public boolean returnToStartPos = true;
        public boolean autoDefaultToPresent = true;
    }

    public static final class AutoDetectConfig {
        public boolean enabled = true;
        public boolean temporaryMode = false;
//        public boolean distanceCheck = false;
        public int temporaryRemovalRange = 32; //blocks

        public final RegistrationArea area = new RegistrationArea();
    }

    public static final class RegistrationArea {
        public boolean enabled = false;
        public int minX, minY, minZ;
        public int maxX, maxY, maxZ;

        public void set(int x1, int y1, int z1, int x2, int y2, int z2) {
            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
            this.maxZ = Math.max(z1, z2);
            this.enabled = true;
        }

        public boolean isInside(int x, int y, int z) {
            if (!enabled) return true;
            return x >= minX && x <= maxX &&
                    y >= minY && y <= maxY &&
                    z >= minZ && z <= maxZ;
        }
    }

    public static final class PlayerPearls {
        public String playerName;
        public String defaultPearlId;
        public Map<String, StoredPearl> pearls = new LinkedHashMap<>();
    }

    public static final class StoredPearl {
        public String pearlId;
        public int x;
        public int y;
        public int z;
    }
}
