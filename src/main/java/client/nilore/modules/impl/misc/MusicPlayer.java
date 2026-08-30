package client.nilore.modules.impl.misc;

import client.nilore.gui.MusicPlayerScreen;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.misc.music.MusicPlaylist;
import client.nilore.modules.impl.misc.music.AudioPlayer;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;

public class MusicPlayer extends Module {
    public static final AudioPlayer AUDIO_PLAYER = new AudioPlayer();
    public static final MusicPlaylist PLAYLIST = new MusicPlaylist();

    private boolean internalVolumeChange = false;

    private final BooleanSetting melodify = new BooleanSetting("Melodify", false) {
        @Override
        public void onChanged(Boolean oldValue, Boolean newValue) {
            MusicPlayer.AUDIO_PLAYER.setMelodifyEnabled(newValue);
        }
    };

    private final NumberSetting crossfade = new NumberSetting("Crossfade", 12, 4, 20, 1) {
        @Override
        public void onChanged(Number oldValue, Number newValue) {
            MusicPlayer.AUDIO_PLAYER.setCrossfadeMs(newValue.intValue() * 1000L);
        }
    };

    private final NumberSetting volume = new NumberSetting("Volume", 90, 0, 100, 1) {
        @Override
        public void onChanged(Number oldValue, Number newValue) {
            if (internalVolumeChange) return;
            float vol = newValue.intValue() / 100f;
            System.out.println("[MusicPlayer] Setting changed: " + oldValue + " -> " + newValue + " (vol=" + vol + ")");
            AUDIO_PLAYER.setVolume(vol);
        }
    };

    public MusicPlayer() {
        super("MusicPlayer", Category.MISC);
        // Crossfade only makes sense when Melodify (preload+crossfade) is on
        crossfade.setVisibility(() -> melodify.getValue());
    }

    @Override
    protected void onEnable() {
        try {
            internalVolumeChange = true;
            volume.setValue((int) (AUDIO_PLAYER.getVolume() * 100));
            internalVolumeChange = false;
            mc.setScreen(new MusicPlayerScreen());
        } catch (Exception e) {
            logger.error("Error opening MusicPlayer", e);
        } finally {
            this.setEnabled(false);
        }
    }

    public void setVolumeSetting(float vol) {
        internalVolumeChange = true;
        volume.setValue((int) (vol * 100));
        internalVolumeChange = false;
    }

    public boolean isMelodifyEnabled() {
        return melodify.getValue();
    }
}
