package client.nilore.modules.impl.misc.music;

public class SongInfo {
    public final long id;
    public final String name;
    public final String artist;
    public final String albumName;
    public final String albumPicUrl;
    public long duration;
    public float chorusStartSec;
    public float chorusDurationSec;

    public SongInfo(long id, String name, String artist, String albumName, String albumPicUrl, long duration) {
        this.id = id;
        this.name = name;
        this.artist = artist;
        this.albumName = albumName;
        this.albumPicUrl = albumPicUrl;
        this.duration = duration;
    }

    public String formatDuration() {
        long totalSec = duration / 1000;
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }
}
