package io.github.zelenusha.buildersmod.maintenance;

import java.util.BitSet;

public final class GraffitiPattern {
    public static final int WIDTH = 16;
    public static final int HEIGHT = 9;
    public static final int CELL = 8;
    public static final int CANVAS_WIDTH = WIDTH * CELL;
    public static final int CANVAS_HEIGHT = HEIGHT * CELL;

    private static final String[] DRAWING = {
            "................",
            ".##..........##.",
            "..###########...",
            ".####.##.######.",
            ".####.##.######.",
            "..###########...",
            "...##.#.#.##....",
            ".....#####...##.",
            "................"
    };
    private static final int[][] PATCHES;
    public static final int PATCH_COUNT;

    static {
        PATCHES = new int[HEIGHT][WIDTH];
        int patch = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                PATCHES[y][x] = DRAWING[y].charAt(x) == '#' ? patch++ : -1;
            }
        }
        PATCH_COUNT = patch;
    }

    private GraffitiPattern() { }

    public static int patchAt(int x, int y) {
        return x >= 0 && y >= 0 && x < WIDTH && y < HEIGHT ? PATCHES[y][x] : -1;
    }

    public static BitSet scrub(int x, int y, int nextX, int nextY) {
        BitSet patches = new BitSet(PATCH_COUNT);
        if (!inside(x, y) || !inside(nextX, nextY)) {
            return patches;
        }
        int dx = nextX - x;
        int dy = nextY - y;
        int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)));
        for (int step = 0; step <= steps; step++) {
            int cellX = (x + dx * step / steps) / CELL;
            int cellY = (y + dy * step / steps) / CELL;
            brush(patches, cellX, cellY);
        }
        return patches;
    }

    private static void brush(BitSet patches, int cellX, int cellY) {
        for (int y = cellY - 1; y <= cellY + 1; y++) {
            for (int x = cellX - 1; x <= cellX + 1; x++) {
                int patch = patchAt(x, y);
                if (patch >= 0) {
                    patches.set(patch);
                }
            }
        }
    }

    public static boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < CANVAS_WIDTH && y < CANVAS_HEIGHT;
    }
}
