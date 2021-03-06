package org.jdownloader.plugins.components.youtube.converter;

import java.io.File;

import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.utils.logging2.LogSource;
import org.jdownloader.controlling.ffmpeg.FFMpegProgress;
import org.jdownloader.controlling.ffmpeg.FFmpeg;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.logging.LogController;
import org.jdownloader.plugins.components.youtube.ExternalToolRequired;

public class YoutubeConverterFLVToMP3Audio extends AbstractDemuxToAudioConverter implements ExternalToolRequired {
    private static final YoutubeConverterFLVToMP3Audio INSTANCE = new YoutubeConverterFLVToMP3Audio();

    /**
     * get the only existing instance of YoutubeMp4ToM4aAudio. This is a singleton
     *
     * @return
     */
    public static YoutubeConverterFLVToMP3Audio getInstance() {
        return YoutubeConverterFLVToMP3Audio.INSTANCE;
    }

    private LogSource logger;

    /**
     * Create a new instance of YoutubeMp4ToM4aAudio. This is a singleton class. Access the only existing instance by using
     * {@link #getInstance()}.
     */
    private YoutubeConverterFLVToMP3Audio() {
        logger = LogController.getInstance().getLogger(YoutubeConverterFLVToMP3Audio.class.getName());
    }

    @Override
    public void run(DownloadLink downloadLink, PluginForHost plugin) throws Exception {
        final FFMpegProgress set = new FFMpegProgress();
        try {
            downloadLink.addPluginProgress(set);
            final File file = new File(downloadLink.getFileOutput());
            plugin.checkFFmpeg(downloadLink, _GUI.T.YoutubeDash_handleDownload_youtube_dash());
            final FFmpeg ffmpeg = new FFmpeg();
            final File finalFile = downloadLink.getDownloadLinkController().getFileOutput(false, true);
            if (!ffmpeg.demuxMp3(set, finalFile.getAbsolutePath(), file.getAbsolutePath())) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, _GUI.T.YoutubeDash_handleFree_error_());
            }
            file.delete();
            downloadLink.setDownloadSize(finalFile.length());
            downloadLink.setDownloadCurrent(finalFile.length());
            try {
                downloadLink.setInternalTmpFilenameAppend(null);
                downloadLink.setInternalTmpFilename(null);
            } catch (final Throwable e) {
            }
        } finally {
            downloadLink.removePluginProgress(set);
        }
    }

}
