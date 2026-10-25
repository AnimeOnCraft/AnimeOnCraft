package ru.animeoncraft.integration.neoforge;

/** Timing and layout independent of the renderer, including low GUI resolutions. */
record TitleAnimationTimeline(int width, int height, int fps, int frames) {
    TitleAnimationTimeline {
        if (width < 16 || width > 1920 || height < 16 || height > 1080 || fps < 1 || fps > 30 || frames < 2 || frames > 600)
            throw new IllegalArgumentException("Invalid title animation");
    }
    long durationMillis() { return Math.round(frames * 1000.0 / fps); }
    int frame(long elapsedMillis) {
        long position = Math.max(0, elapsedMillis) % durationMillis();
        return Math.min(frames - 1, (int) (position * fps / 1000));
    }
    float opacity(long elapsedMillis) {
        long remaining = durationMillis() - Math.max(0, elapsedMillis) % durationMillis();
        return Math.min(1f, remaining / 450f);
    }
    record Layout(int x, int y, int width, int row, int play, int gap) {
        int bottom() { return y + play + 3 * (row + gap); }
        int half() { return (width - gap) / 2; }
    }
    static Layout layout(int width, int height) {
        int row = height < 320 ? 20 : 26, play = row + 8, gap = 6;
        int panel = Math.min(300, Math.max(180, width - 40));
        int total = play + 3 * (row + gap);
        int y = Math.min((int) (height * .45), height - total - 30);
        return new Layout((width - panel) / 2, Math.max(80, y), panel, row, play, gap);
    }
}
