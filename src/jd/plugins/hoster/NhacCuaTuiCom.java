package jd.plugins.hoster;

import java.util.LinkedHashMap;

import jd.PluginWrapper;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

/**
 * @author noone2407
 */
@HostPlugin(revision = "$Revision:$", interfaceVersion = 2, names = { "nhaccuatui.com" }, urls = { "http://(www\\.)?nhaccuatui\\.com/bai-hat/\\S+" }, flags = { 0 })
public class NhacCuaTuiCom extends PluginForHost {

    private String dllink = null;

    public NhacCuaTuiCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.nhaccuatui.com/thoa_thuan_su_dung.html";
    }

    @Override
    public AvailableStatus requestFileInformation(DownloadLink downloadLink) throws Exception {
        this.setBrowserExclusive();
        String url = downloadLink.getDownloadURL();
        br.setFollowRedirects(true);
        br.getPage(url);
        String filename = br.getRegex("<h1 itemprop=\"name\">(.*?)<\\/h1>").getMatch(0);
        if (filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        downloadLink.setFinalFileName(filename + ".mp3");

        final String datacode = br.getRegex("<input type=\"hidden\" value=\"([a-zA-Z0-9]+)\" id=\"inpHiddenSongKey\"/>").getMatch(0);
        final String json_source = br.getPage("http://www.nhaccuatui.com/download/song/" + datacode);
        final LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) DummyScriptEnginePlugin.jsonToJavaObject(json_source);
        final String message = (String) DummyScriptEnginePlugin.walkJson(entries, "error_message");
        if (!"Success".equals(message)) {
            return AvailableStatus.FALSE;
        }
        final String streamUrl = (String) DummyScriptEnginePlugin.walkJson(entries, "data/stream_url");
        final String ischarge = (String) DummyScriptEnginePlugin.walkJson(entries, "data/is_charge");
        if (ischarge != null && ischarge.equals("true")) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        }
        dllink = streamUrl;
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, true, 0);
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink downloadLink) {
    }

}
