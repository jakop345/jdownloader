package jd.plugins.hoster;

import java.net.URL;
import java.security.InvalidKeyException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.Property;
import jd.config.SubConfiguration;
import jd.controlling.AccountController;
import jd.gui.UserIO;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Base64;
import jd.nutils.encoding.Encoding;
import jd.nutils.nativeintegration.LocalBrowser;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "pornhub.com" }, urls = { "https?://(?:www\\.|[a-z]{2}\\.)?pornhub\\.com/photo/\\d+|http://pornhubdecrypted\\d+" }, flags = { 2 })
public class PornHubCom extends PluginForHost {

    /* Connection stuff */
    private static final boolean                  FREE_RESUME               = true;
    private static final int                      FREE_MAXCHUNKS            = 0;
    private static final int                      FREE_MAXDOWNLOADS         = 20;
    private static final boolean                  ACCOUNT_FREE_RESUME       = true;
    private static final int                      ACCOUNT_FREE_MAXCHUNKS    = 0;
    private static final int                      ACCOUNT_FREE_MAXDOWNLOADS = 20;

    private static final String                   type_photo                = "https?://(www\\.|[a-z]{2}\\.)?pornhub\\.com/photo/\\d+";
    public static final String                    html_privatevideo         = "id=\"iconLocked\"";
    private String                                dlUrl                     = null;

    public static LinkedHashMap<String, String[]> formats                   = new LinkedHashMap<String, String[]>() {
                                                                                {
                                                                                    /*
                                                                                     * Format-name:videoCodec, videoBitrate,
                                                                                     * videoResolution, audioCodec, audioBitrate
                                                                                     */
                                                                                    /*
                                                                                     * Video-bitrates and resultions here are not exact as
                                                                                     * they vary.
                                                                                     */
                                                                                    /**
                                                                                     * TODO: Check for other resolutions, check: 180p, 1080p
                                                                                     */
                                                                                    put("240", new String[] { "AVC", "400", "420x240", "AAC LC", "54" });
                                                                                    put("480", new String[] { "AVC", "600", "850x480", "AAC LC", "54" });
                                                                                    put("720", new String[] { "AVC", "1500", "1280x720", "AAC LC", "54" });

                                                                                }
                                                                            };
    public static final String                    BEST_ONLY                 = "BEST_ONLY";
    public static final String                    FAST_LINKCHECK            = "FAST_LINKCHECK";

    @SuppressWarnings("deprecation")
    public PornHubCom(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("http://www.pornhub.com/create_account");
        this.setConfigElements();
    }

    @SuppressWarnings("deprecation")
    public void correctDownloadLink(final DownloadLink link) {
        link.setUrlDownload(correctAddedURL(link.getDownloadURL()));
    }

    public static String correctAddedURL(final String input) {
        String output = input.replaceAll("https://", "http://");
        output = input.replaceAll("^http://(www\\.)?([a-z]{2}\\.)?", "http://www.");
        output = input.replaceAll("/embed/", "/view_video.php?viewkey=");
        return output;
    }

    @Override
    public String getAGBLink() {
        return "http://www.pornhub.com/terms";
    }

    @SuppressWarnings({ "deprecation", "static-access" })
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        String source_url = downloadLink.getStringProperty("mainlink");
        /* User-chosen quality, set in decrypter */
        final String quality = downloadLink.getStringProperty("quality", null);
        LinkedHashMap<String, String> fresh_directurls = null;
        final String fid;
        boolean isVideo = true;
        if (downloadLink.getDownloadURL().matches(type_photo)) {
            isVideo = false;
            fid = new Regex(downloadLink.getDownloadURL(), "([A-Za-z0-9\\-_]+)$").getMatch(0);
            /* Offline links should also have nice filenames */
            downloadLink.setName(fid + ".jpg");
            br.getPage(downloadLink.getDownloadURL());
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            dlUrl = br.getRegex("name=\"twitter:image:src\" content=\"(http[^<>\"]*?\\.[A-Za-z]{3,5})\"").getMatch(0);
            if (dlUrl == null) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            downloadLink.setFinalFileName(fid + dlUrl.substring(dlUrl.lastIndexOf(".")));
        } else {
            String filename = downloadLink.getStringProperty("decryptedfilename", null);
            dlUrl = downloadLink.getStringProperty("directlink", null);
            prepBr(this.br);
            final Account aa = AccountController.getInstance().getValidAccount(this);
            if (aa != null) {
                try {
                    this.login(this.br, aa, false);
                } catch (final Throwable e) {
                }
            }
            if (dlUrl == null) {
                // Handle links that were grabbed before decrypter exist (20150831)
                // TODO: Remove 2016
                source_url = downloadLink.getDownloadURL();
                this.br.setFollowRedirects(true);
            }

            fid = new Regex(source_url, "([A-Za-z0-9\\-_]+)$").getMatch(0);
            /* Offline links should also have nice filenames */
            downloadLink.setName(fid + ".mp4");

            this.br.getPage(source_url);
            if (br.containsHTML(html_privatevideo)) {
                downloadLink.getLinkStatus().setStatusText("You're not authorized to watch/download this private video");
                downloadLink.setName(filename);
                return AvailableStatus.TRUE;
            }
            if (dlUrl == null) {
                // Handle links that were grabbed before decrypter exist (20150831)
                // TODO: Remove 2016
                String[] qualities = { "720", "480", "240" };
                final SubConfiguration cfg = SubConfiguration.getConfig("pornhub.com");
                for (String qualityInfo : qualities) {
                    if (cfg.getBooleanProperty(qualityInfo, true)) {
                        dlUrl = br.getRegex(qualityInfo + "p = \'(http://[^\']*?)\'").getMatch(0);
                        filename = getSiteTitle(br) + "." + qualityInfo + "p.mp4";
                    }
                    if (dlUrl != null) {
                        break;
                    }
                }
            }
            if (source_url == null || filename == null || this.dlUrl == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            fresh_directurls = getVideoLinksFree(this.br);
            downloadLink.setFinalFileName(filename);
        }
        setBrowserExclusive();
        br.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = this.br.openHeadConnection(dlUrl);
            if (con.getResponseCode() != 200) {
                if (fresh_directurls == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                this.dlUrl = fresh_directurls.get(quality);
                if (this.dlUrl == null) {
                    logger.warning("Failed to get fresh directurl");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                con.disconnect();
                /* Last chance */
                con = this.br.openHeadConnection(dlUrl);
                if (con.getResponseCode() != 200) {
                    con.disconnect();
                    br.getPage(source_url);
                    this.dlUrl = fresh_directurls.get(quality);
                    if (this.dlUrl == null) {
                        logger.warning("Failed to get fresh directurl");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    con = this.br.openHeadConnection(dlUrl);
                }
                if (con.getResponseCode() != 200) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            if (con.getContentType().contains("html")) {
                /* Undefined case but probably that url is offline! */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            downloadLink.setDownloadSize(br.getHttpConnection().getLongContentLength());
            return AvailableStatus.TRUE;
        } finally {
            try {
                if (con != null) {
                    con.disconnect();
                }
            } catch (final Throwable e) {
                logger.info("e: " + e);
            }
        }
    }

    // private void getVideoLinkAccount() {
    // dlUrl = this.br.getRegex("class=\"downloadBtn greyButton\" (target=\"_blank\")? href=\"(http[^<>\"]*?)\"").getMatch(1);
    // }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static LinkedHashMap<String, String> getVideoLinksFree(final Browser br) throws Exception {
        final LinkedHashMap qualities = new LinkedHashMap<String, String>();
        String flashVars = br.getRegex("\\'flashvars\\' :[\t\n\r ]+\\{([^\\}]+)").getMatch(0);
        if (flashVars == null) {
            flashVars = br.getRegex("var flashvars_\\d+ = (\\{.*?);\n").getMatch(0);
        }
        if (flashVars == null) {
            return null;
        }
        final LinkedHashMap<String, Object> values = (LinkedHashMap<String, Object>) jd.plugins.hoster.DummyScriptEnginePlugin.jsonToJavaObject(flashVars);

        if (values == null || values.size() < 1) {
            return null;
        }
        boolean success = false;
        String dllink_temp = null;
        // dllink_temp = (String) values.get("video_url");
        for (Entry<String, Object> next : values.entrySet()) {
            String key = next.getKey();
            if (!(key.startsWith("quality_"))) {
                continue;
            }
            String quality = new Regex(key, "quality_(\\d+)p").getMatch(0);
            if (quality == null) {
                continue;
            }
            dllink_temp = (String) next.getValue();
            final Boolean encrypted = values.get("encrypted") == null ? null : ((Boolean) values.get("encrypted")).booleanValue();
            if (encrypted == Boolean.TRUE) {
                final String decryptkey = (String) values.get("video_title");
                try {
                    dllink_temp = new BouncyCastleAESCounterModeDecrypt().decrypt(dllink_temp, decryptkey, 256);
                } catch (Throwable e) {
                    /* Fallback for stable version */
                    dllink_temp = AESCounterModeDecrypt(dllink_temp, decryptkey, 256);
                }
                if (dllink_temp != null && (dllink_temp.startsWith("Error:") || !dllink_temp.startsWith("http"))) {
                    success = false;
                } else {
                    success = true;
                }
            } else {
                success = true;
            }
            qualities.put(quality, next.getValue());
        }

        if (!success) {
            final String[] quals = new String[] { "1080", "720", "480", "360", "240" };
            // seems they have seperated into multiple vars
            final String[][] var_player_quality_dp = br.getRegex("var player_quality_(1080|720|480|360|240)p\\s*=\\s*('|\")(https?://.*?)\\2\\s*;").getMatches();
            for (final String q : quals) {
                for (final String[] var : var_player_quality_dp) {
                    // so far any of these links will work.
                    if (var[0].equals(q)) {
                        qualities.put(q, var[2]);
                    }
                }
            }

        }
        return qualities;
    }

    public static String getSiteTitle(final Browser br) {
        String site_title = br.getRegex("<title>([^<>]*?) \\- Pornhub\\.com</title>").getMatch(0);
        if (site_title == null) {
            site_title = br.getRegex("\"section_title overflow\\-title overflow\\-title\\-width\">([^<>]*?)</h1>").getMatch(0);
        }
        if (site_title != null) {
            site_title = encodeUnicode(site_title);
        }
        return site_title;
    }

    /** Avoid chars which are not allowed in filenames under certain OS' */
    private static String encodeUnicode(final String input) {
        String output = input;
        output = output.replace(":", ";");
        output = output.replace("|", "¦");
        output = output.replace("<", "[");
        output = output.replace(">", "]");
        output = output.replace("/", "⁄");
        output = output.replace("\\", "∖");
        output = output.replace("*", "#");
        output = output.replace("?", "¿");
        output = output.replace("!", "¡");
        output = output.replace("\"", "'");
        return output;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        doFree(downloadLink);
    }

    @SuppressWarnings("deprecation")
    private void doFree(final DownloadLink downloadLink) throws Exception, PluginException {
        final boolean resume;
        final int maxchunks;
        if (downloadLink.getDownloadURL().matches(type_photo)) {
            resume = true;
            /* We only have small pictures --> No chunkload needed */
            maxchunks = 1;
            requestFileInformation(downloadLink);
        } else {
            resume = ACCOUNT_FREE_RESUME;
            maxchunks = ACCOUNT_FREE_MAXCHUNKS;
            if (br.containsHTML(html_privatevideo)) {
                throw new PluginException(LinkStatus.ERROR_FATAL, "You're not authorized to watch/download this private video");
            }
            if (dlUrl == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dlUrl, resume, maxchunks);
        if (dl.getConnection().getContentType().contains("html")) {
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            }
            try {
                dl.getConnection().disconnect();
            } catch (final Throwable e) {
            }
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    private static final String MAINPAGE        = "http://pornhub.com";
    private static final String PORNHUB_PREMIUM = "http://pornhubpremium.com";
    private static Object       LOCK            = new Object();

    @SuppressWarnings("unchecked")
    public static void login(final Browser br, final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                // Load cookies
                br.setCookiesExclusive(true);
                final Object ret = account.getProperty("cookies", null);
                boolean acmatch = Encoding.urlEncode(account.getUser()).equals(account.getStringProperty("name", Encoding.urlEncode(account.getUser())));
                if (acmatch) {
                    acmatch = Encoding.urlEncode(account.getPass()).equals(account.getStringProperty("pass", Encoding.urlEncode(account.getPass())));
                }
                if (acmatch && ret != null && ret instanceof HashMap<?, ?> && !force) {
                    final HashMap<String, String> cookies = (HashMap<String, String>) ret;
                    if (account.isValid()) {
                        for (final Map.Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            br.setCookie(MAINPAGE, key, value);
                        }
                        return;
                    }
                }
                br.setFollowRedirects(false);
                br.getPage("http://www.pornhub.com/");
                final String login_key = br.getRegex("id=\"login_key\" value=\"([^<>\"]*?)\"").getMatch(0);
                final String login_hash = br.getRegex("id=\"login_hash\" value=\"([^<>\"]*?)\"").getMatch(0);
                if (login_key == null || login_hash == null) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin defekt, bitte den JDownloader Support kontaktieren!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else if ("pl".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nBłąd wtyczki, skontaktuj się z Supportem JDownloadera!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin broken, please contact the JDownloader Support!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                final String postData = "username=" + Encoding.urlEncode(account.getUser()) + "&password=" + Encoding.urlEncode(account.getPass()) + "&redirect=&login_key=" + login_key + "&login_hash=" + login_hash + "&remember_me=1";
                br.postPage("/front/login_json", postData);
                final String redirect = getJson(br.toString(), "redirect");
                if (redirect != null) {
                    /* Sometimes needed to get the (premium) cookies. */
                    br.getPage(redirect);
                }
                final String cookie_loggedin_free = br.getCookie(MAINPAGE, "gateway_security_key");
                final String cookie_loggedin_premium = br.getCookie(PORNHUB_PREMIUM, "gateway_security_key");
                if (cookie_loggedin_free == null && cookie_loggedin_premium == null) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                // Save cookies
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = br.getCookies(MAINPAGE);
                for (final Cookie c : add.getCookies()) {
                    cookies.put(c.getKey(), c.getValue());
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", cookies);
            } catch (final PluginException e) {
                account.setProperty("cookies", Property.NULL);
                throw e;
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        try {
            login(this.br, account, true);
        } catch (PluginException e) {
            account.setValid(false);
            throw e;
        }
        ai.setUnlimitedTraffic();
        if (this.br.getURL().contains("pornhubpremium.com/")) {
            account.setType(AccountType.PREMIUM);
            /* Premium accounts can still have captcha */
            account.setMaxSimultanDownloads(ACCOUNT_FREE_MAXDOWNLOADS);
            account.setConcurrentUsePossible(false);
            ai.setStatus("Premium user");
        } else {
            account.setType(AccountType.FREE);
            /* Free accounts can still have captcha */
            account.setMaxSimultanDownloads(ACCOUNT_FREE_MAXDOWNLOADS);
            account.setConcurrentUsePossible(false);
            ai.setStatus("Registered (free) user");
        }
        account.setValid(true);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        /* No need to login as we're already logged in. */
        doFree(link);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return ACCOUNT_FREE_MAXDOWNLOADS;
    }

    /**
     * AES CTR(Counter) Mode for Java ported from AES-CTR-Mode implementation in JavaScript by Chris Veness
     *
     * @see <a
     *      href="http://csrc.nist.gov/publications/nistpubs/800-38a/sp800-38a.pdf">"Recommendation for Block Cipher Modes of Operation - Methods and Techniques"</a>
     */
    public static String AESCounterModeDecrypt(String cipherText, String key, int nBits) throws Exception {
        if (!(nBits == 128 || nBits == 192 || nBits == 256)) {
            return "Error: Must be a key mode of either 128, 192, 256 bits";
        }
        if (cipherText == null || key == null) {
            return "Error: cipher and/or key equals null";
        }
        String res = null;
        nBits = nBits / 8;
        byte[] data = Base64.decode(cipherText.toCharArray());
        /* CHECK: we should always use getBytes("UTF-8") or with wanted charset, never system charset! */
        byte[] k = Arrays.copyOf(key.getBytes(), nBits);

        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        SecretKey secretKey = generateSecretKey(k, nBits);
        byte[] nonceBytes = Arrays.copyOf(Arrays.copyOf(data, 8), nBits / 2);
        IvParameterSpec nonce = new IvParameterSpec(nonceBytes);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, nonce);
        /* CHECK: we should always use new String (bytes,charset) to avoid issues with system charset and utf-8 */
        res = new String(cipher.doFinal(data, 8, data.length - 8));
        return res;
    }

    public static SecretKey generateSecretKey(byte[] keyBytes, int nBits) throws Exception {
        try {
            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            keyBytes = cipher.doFinal(keyBytes);
        } catch (InvalidKeyException e) {
            if (e.getMessage().contains("Illegal key size")) {
                getPolicyFiles();
            }
            throw new PluginException(LinkStatus.ERROR_FATAL, "Unlimited Strength JCE Policy Files needed!");
        } catch (Throwable e1) {
            return null;
        }
        System.arraycopy(keyBytes, 0, keyBytes, nBits / 2, nBits / 2);
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static class BouncyCastleAESCounterModeDecrypt {
        private String decrypt(String cipherText, String key, int nBits) throws Exception {
            if (!(nBits == 128 || nBits == 192 || nBits == 256)) {
                return "Error: Must be a key mode of either 128, 192, 256 bits";
            }
            if (cipherText == null || key == null) {
                return "Error: cipher and/or key equals null";
            }
            byte[] decrypted;
            nBits = nBits / 8;
            byte[] data = Base64.decode(cipherText.toCharArray());
            /* CHECK: we should always use getBytes("UTF-8") or with wanted charset, never system charset! */
            byte[] k = Arrays.copyOf(key.getBytes(), nBits);
            /* AES/CTR/NoPadding (SIC == CTR) */
            org.bouncycastle.crypto.BufferedBlockCipher cipher = new org.bouncycastle.crypto.BufferedBlockCipher(new org.bouncycastle.crypto.modes.SICBlockCipher(new org.bouncycastle.crypto.engines.AESEngine()));
            cipher.reset();
            SecretKey secretKey = generateSecretKey(k, nBits);
            byte[] nonceBytes = Arrays.copyOf(Arrays.copyOf(data, 8), nBits / 2);
            IvParameterSpec nonce = new IvParameterSpec(nonceBytes);
            /* true == encrypt; false == decrypt */
            cipher.init(true, new org.bouncycastle.crypto.params.ParametersWithIV(new org.bouncycastle.crypto.params.KeyParameter(secretKey.getEncoded()), nonce.getIV()));
            decrypted = new byte[cipher.getOutputSize(data.length - 8)];
            int decLength = cipher.processBytes(data, 8, data.length - 8, decrypted, 0);
            cipher.doFinal(decrypted, decLength);
            /* CHECK: we should always use new String (bytes,charset) to avoid issues with system charset and utf-8 */
            return new String(decrypted);
        }

        private SecretKey generateSecretKey(byte[] keyBytes, int nBits) throws Exception {
            try {
                SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
                /* AES/ECB/NoPadding */
                org.bouncycastle.crypto.BufferedBlockCipher cipher = new org.bouncycastle.crypto.BufferedBlockCipher(new org.bouncycastle.crypto.engines.AESEngine());
                cipher.init(true, new org.bouncycastle.crypto.params.KeyParameter(secretKey.getEncoded()));
                keyBytes = new byte[cipher.getOutputSize(secretKey.getEncoded().length)];
                int decLength = cipher.processBytes(secretKey.getEncoded(), 0, secretKey.getEncoded().length, keyBytes, 0);
                cipher.doFinal(keyBytes, decLength);
            } catch (Throwable e) {
                return null;
            }
            System.arraycopy(keyBytes, 0, keyBytes, nBits / 2, nBits / 2);
            return new SecretKeySpec(keyBytes, "AES");
        }
    }

    public static void prepBr(final Browser br) {
        br.setCookie("http://pornhub.com/", "age_verified", "1");
        br.setCookie("http://pornhub.com/", "is_really_pc", "1");
        br.setCookie("http://pornhub.com/", "phub_in_player", "1");
        br.getHeaders().put("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:22.0) Gecko/20100101 Firefox/22.0");
        br.getHeaders().put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        br.getHeaders().put("Accept-Language", "en-US,en;q=0.5");
        br.getHeaders().put("Accept-Charset", null);
        br.setLoadLimit(br.getLoadLimit() * 4);
    }

    public static void getPolicyFiles() throws Exception {
        int ret = -100;
        UserIO.setCountdownTime(120);
        ret = UserIO.getInstance().requestConfirmDialog(UserIO.STYLE_LARGE, "Java Cryptography Extension (JCE) Error: 32 Byte keylength is not supported!", "At the moment your Java version only supports a maximum keylength of 16 Bytes but the keezmovies plugin needs support for 32 byte keys.\r\nFor such a case Java offers so called \"Policy Files\" which increase the keylength to 32 bytes. You have to copy them to your Java-Home-Directory to do this!\r\nExample path: \"jre6\\lib\\security\\\". The path is different for older Java versions so you might have to adapt it.\r\n\r\nBy clicking on CONFIRM a browser instance will open which leads to the downloadpage of the file.\r\n\r\nThanks for your understanding.", null, "CONFIRM", "Cancel");
        if (ret != -100) {
            if (UserIO.isOK(ret)) {
                LocalBrowser.openDefaultURL(new URL("http://www.oracle.com/technetwork/java/javase/downloads/jce-6-download-429243.html"));
                LocalBrowser.openDefaultURL(new URL("http://h10.abload.de/img/jcedp50.png"));
            } else {
                return;
            }
        }
    }

    /**
     * Wrapper<br/>
     * Tries to return value of key from JSon response, from String source.
     *
     * @author raztoki
     * */
    public static String getJson(final String source, final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJson(source, key);
    }

    /* NO OVERRIDE!! We need to stay 0.9*compatible */
    public boolean allowHandle(final DownloadLink downloadLink, final PluginForHost plugin) {
        return downloadLink.getHost().equalsIgnoreCase(plugin.getHost());
    }

    @Override
    public String getDescription() {
        return "JDownloader's Pornhub plugin helps downloading videoclips from pornhub.com.";
    }

    private void setConfigElements() {
        final ConfigEntry best = new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), BEST_ONLY, JDL.L("plugins.hoster.PornHubCom.BestOnly", "Always only grab the best resolution available?")).setDefaultValue(false);
        getConfig().addEntry(best);
        final Iterator<Entry<String, String[]>> it = formats.entrySet().iterator();
        while (it.hasNext()) {
            /*
             * Format-name:videoCodec, videoBitrate, videoResolution, audioCodec, audioBitrate
             */
            String usertext = "Load ";
            final Entry<String, String[]> videntry = it.next();
            final String internalname = videntry.getKey();
            final String[] vidinfo = videntry.getValue();
            final String videoCodec = vidinfo[0];
            final String videoBitrate = vidinfo[1];
            final String videoResolution = vidinfo[2];
            final String audioCodec = vidinfo[3];
            final String audioBitrate = vidinfo[4];
            if (videoCodec != null) {
                usertext += videoCodec + " ";
            }
            if (videoBitrate != null) {
                usertext += videoBitrate + " ";
            }
            if (videoResolution != null) {
                usertext += videoResolution + " ";
            }
            if (audioCodec != null || audioBitrate != null) {
                usertext += "with audio ";
                if (audioCodec != null) {
                    usertext += audioCodec + " ";
                }
                if (audioBitrate != null) {
                    usertext += audioBitrate;
                }
            }
            if (usertext.endsWith(" ")) {
                usertext = usertext.substring(0, usertext.lastIndexOf(" "));
            }
            final ConfigEntry vidcfg = new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), internalname, JDL.L("plugins.hoster.PornHubCom.ALLOW_" + internalname, usertext)).setDefaultValue(true).setEnabledCondidtion(best, false);
            getConfig().addEntry(vidcfg);
        }
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), FAST_LINKCHECK, JDL.L("plugins.hoster.PornHubCom.FastLinkcheck", "Enable fast linkcheck?\r\nNOTE: If enabled, links will appear faster but filesize won't be shown before downloadstart.")).setDefaultValue(false));
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

}