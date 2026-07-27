package net.fabricmc.loader.api;

import java.nio.file.Path;

/** Test double used by the portable Fabric entrypoint integration test. */
public final class FabricLoader {
    private static final FabricLoader INSTANCE = new FabricLoader();
    private static Path gameDirectory;

    private FabricLoader() {
    }

    public static FabricLoader getInstance() {
        return INSTANCE;
    }

    public static void setGameDir(Path gameDirectory) {
        FabricLoader.gameDirectory = gameDirectory;
    }

    public Path getGameDir() {
        return gameDirectory;
    }
}
