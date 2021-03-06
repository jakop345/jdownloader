//    jDownloader - Downloadmanager
//    Copyright (C) 2013  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import org.appwork.exceptions.WTFException;
import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.storage.config.ConfigInterface;
import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.CustomStorageName;
import org.appwork.storage.config.annotations.DefaultBooleanValue;
import org.appwork.storage.config.handler.BooleanKeyHandler;
import org.appwork.storage.config.handler.KeyHandler;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.swing.EDTRunner;
import org.jdownloader.captcha.v2.AbstractResponse;
import org.jdownloader.captcha.v2.ChallengeResponseController;
import org.jdownloader.captcha.v2.ChallengeSolver;
import org.jdownloader.captcha.v2.challenge.oauth.AccountLoginOAuthChallenge;
import org.jdownloader.captcha.v2.solverjob.SolverJob;
import org.jdownloader.plugins.components.realDebridCom.api.Error;
import org.jdownloader.plugins.components.realDebridCom.api.json.ClientSecret;
import org.jdownloader.plugins.components.realDebridCom.api.json.CodeResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.ErrorResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.HostsResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.TokenResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.UnrestrictLinkResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.UserResponse;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.plugins.controller.host.LazyHostPlugin.FEATURE;
import org.jdownloader.translate._JDT;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.Property;
import jd.config.SubConfiguration;
import jd.gui.swing.jdgui.views.settings.components.Checkbox;
import jd.http.Browser;
import jd.http.QueryInfo;
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginConfigPanelNG;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.download.DownloadLinkDownloadable;
import jd.plugins.download.HashInfo;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "real-debrid.com" }, urls = { "https?://\\w+(\\.download)?\\.(?:real\\-debrid\\.com|rdb\\.so|rdeb\\.io)/dl?/\\w+/.+" }, flags = { 2 })
public class RealDebridCom extends PluginForHost {

    private static final String CLIENT_SECRET_KEY = "client_secret";
    private static final String CLIENT_ID_KEY     = "client_id";
    private static final String CLIENT_SECRET     = "CLIENT_SECRET";
    private static final String TOKEN             = "TOKEN";

    private static final String AUTHORIZATION     = "Authorization";

    private static class APIException extends Exception {

        private URLConnectionAdapter connection;

        public APIException(URLConnectionAdapter connection, Error error, String msg) {
            super(msg);
            this.error = error;
            this.connection = connection;
        }

        public URLConnectionAdapter getConnection() {
            return connection;
        }

        private Error error;

        public Error getError() {
            return error;
        }

    }

    private static final String                            API                = "https://api.real-debrid.com";
    private static final String                            CLIENT_ID          = "NJ26PAPGHWGZY";
    // DEV NOTES
    // supports last09 based on pre-generated links and jd2 (but disabled with interfaceVersion 3)
    private static HashMap<Account, HashMap<String, Long>> hostUnavailableMap = new HashMap<Account, HashMap<String, Long>>();
    private static Object                                  LOCK               = new Object();
    private static AtomicInteger                           MAX_DOWNLOADS      = new AtomicInteger(Integer.MAX_VALUE);
    private static AtomicInteger                           RUNNING_DOWNLOADS  = new AtomicInteger(0);

    private final String                                   mName              = "real-debrid.com";

    private final String                                   mProt              = "https://";

    private Browser                                        apiBrowser;
    private RealDebridConfigPanel                          panel;
    private TokenResponse                                  token;
    private Account                                        account;
    protected ClientSecret                                 clientSecret;

    public RealDebridCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(mProt + mName + "/");

        Browser.setRequestIntervalLimitGlobal(getHost(), 500);
        Browser.setRequestIntervalLimitGlobal("rdb.so", 500);
        Browser.setRequestIntervalLimitGlobal("rdeb.io", 500);
    }

    private <T> T callRestAPI(String method, QueryInfo query, TypeRef<T> type) throws Exception {
        Request request;
        ensureAPIBrowser();
        login(account, false);
        try {
            return callRestAPIInternal("https://api.real-debrid.com/rest/1.0" + method, query, type);
        } catch (APIException e) {
            switch (e.getError()) {
            case BAD_LOGIN:
            case BAD_TOKEN:
                // refresh Token

                login(account, true);
                return callRestAPIInternal("https://api.real-debrid.com/rest/1.0" + method, query, type);
            default:
                throw e;
            }
        }
    }

    protected synchronized <T> T callRestAPIInternal(String url, QueryInfo query, TypeRef<T> type) throws IOException, APIException {

        Request request;
        if (token != null) {
            apiBrowser.getHeaders().put(AUTHORIZATION, "Bearer " + token.getAccess_token());
        }

        if (query != null) {
            request = apiBrowser.createPostRequest(url, query);
        } else {
            request = apiBrowser.createGetRequest(url);
        }
        String json = apiBrowser.getPage(request);
        if (request.getHttpConnection().getResponseCode() != 200) {
            if (json.trim().startsWith("{")) {
                ErrorResponse errorResponse = JSonStorage.restoreFromString(json, new TypeRef<ErrorResponse>(ErrorResponse.class) {
                });
                throw new APIException(request.getHttpConnection(), Error.getByCode(errorResponse.getError_code()), errorResponse.getError());
            } else {
                throw new IOException("Unexpected Response: " + json);
            }
        }
        return JSonStorage.restoreFromString(json, type);

    }

    private void ensureAPIBrowser() {
        if (apiBrowser == null) {
            apiBrowser = br.cloneBrowser();
            for (int i = 200; i < 600; i++) {
                apiBrowser.addAllowedResponseCodes(i);
            }
        }
    }

    private <T> T callRestAPI(String method, TypeRef<T> type) throws Exception {
        return callRestAPI(method, null, type);

    }

    @Override
    public boolean canHandle(DownloadLink downloadLink, Account account) {
        if (isDirectLink(downloadLink)) {
            // generated links do not require an account to download
            return true;
        } else if (account == null) {
            // no non account handleMultiHost support.
            return false;
        } else {
            return true;
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        this.account = account;

        account.setError(null, null);
        account.setConcurrentUsePossible(true);
        account.setMaxSimultanDownloads(-1);

        UserResponse user = callRestAPI("/user", new TypeRef<UserResponse>(UserResponse.class) {
        });

        ai.setValidUntil(TimeFormatter.getTimestampByGregorianTime(user.getExpiration()));

        if ("premium".equalsIgnoreCase(user.getType())) {
            ai.setStatus("Premium User");
            account.setType(AccountType.PREMIUM);

        } else {
            account.setType(AccountType.FREE);

            ai.setProperty("multiHostSupport", Property.NULL);
            return ai;
        }
        HashMap<String, HostsResponse> hosts = callRestAPI("/hosts", new TypeRef<HashMap<String, HostsResponse>>() {
        });

        ArrayList<String> supportedHosts = new ArrayList<String>();
        for (Entry<String, HostsResponse> es : hosts.entrySet()) {
            if (StringUtils.isNotEmpty(es.getKey())) {
                supportedHosts.add(es.getKey());
            }

        }
        ai.setMultiHostSupport(this, supportedHosts);

        return ai;
    }

    @Override
    public String getAGBLink() {
        return mProt + mName + "/terms";
    }

    @Override
    public FEATURE[] getFeatures() {
        return new FEATURE[] { FEATURE.MULTIHOST };
    }

    @Override
    public int getMaxSimultanDownload(DownloadLink link, final Account account) {
        return MAX_DOWNLOADS.get();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return MAX_DOWNLOADS.get();
    }

    private void handleDL(final Account acc, final DownloadLink link, final String dllink, final UnrestrictLinkResponse linkresp) throws Exception {
        // real debrid connections are flakey at times! Do this instead of repeating download steps.
        final int maxChunks;
        if (linkresp == null) {
            maxChunks = 0;
        } else {
            if (linkresp.getChunks() <= 0 || PluginJsonConfig.get(RealDebridComConfig.class).isIgnoreServerSideChunksNum()) {
                maxChunks = 0;
            } else {
                maxChunks = -(int) linkresp.getChunks();
            }
        }

        final String host = Browser.getHost(dllink);
        final DownloadLinkDownloadable downloadLinkDownloadable = new DownloadLinkDownloadable(link) {
            @Override
            public HashInfo getHashInfo() {
                if (linkresp == null || linkresp.getCrc() == 1) {
                    return super.getHashInfo();
                } else {
                    return null;
                }
            }

            @Override
            public String getHost() {
                return host;
            }
        };
        final Browser br2 = br.cloneBrowser();
        br2.setAllowedResponseCodes(new int[0]);
        boolean increment = false;
        try {
            dl = jd.plugins.BrowserAdapter.openDownload(br2, downloadLinkDownloadable, br2.createGetRequest(dllink), true, maxChunks);
            if (dl.getConnection().isContentDisposition() || StringUtils.containsIgnoreCase(dl.getConnection().getContentType(), "octet-stream")) {
                /* content disposition, lets download it */
                RUNNING_DOWNLOADS.incrementAndGet();
                increment = true;
                boolean ret = dl.startDownload();
                if (ret && link.getLinkStatus().hasStatus(LinkStatus.FINISHED)) {
                    // download is 100%
                    return;
                }

            }

        } finally {
            try {
                dl.getConnection().disconnect();
            } catch (final Throwable t) {
            }
            if (increment && RUNNING_DOWNLOADS.decrementAndGet() == 0) {
                MAX_DOWNLOADS.set(Integer.MAX_VALUE);
            }
        }

    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        showMessage(link, "Task 1: Check URL validity!");
        final AvailableStatus status = requestFileInformation(link);
        if (AvailableStatus.UNCHECKABLE.equals(status)) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 60 * 1000l);
        }
        showMessage(link, "Task 2: Download begins!");
        handleDL(account, link, link.getPluginPatternMatcher(), null);
    }

    @Override
    public boolean hasConfig() {
        return true;
    }

    /** no override to keep plugin compatible to old stable */
    public void handleMultiHost(final DownloadLink link, final Account account) throws Exception {
        this.account = account;
        try {
            synchronized (hostUnavailableMap) {
                HashMap<String, Long> unavailableMap = hostUnavailableMap.get(account);
                if (unavailableMap != null) {
                    Long lastUnavailable = unavailableMap.get(link.getHost());
                    if (lastUnavailable != null && System.currentTimeMillis() < lastUnavailable) {
                        final long wait = lastUnavailable - System.currentTimeMillis();
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Host is temporarily unavailable via " + this.getHost(), wait);
                    } else if (lastUnavailable != null) {
                        unavailableMap.remove(link.getHost());
                        if (unavailableMap.size() == 0) {
                            hostUnavailableMap.remove(account);
                        }
                    }
                }
            }

            prepBrowser(br);
            login(account, false);
            showMessage(link, "Task 1: Generating Link");
            /* request Download */
            String dllink = link.getDefaultPlugin().buildExternalDownloadURL(link, this);
            UnrestrictLinkResponse linkresp = callRestAPI("/unrestrict/link", new QueryInfo().append("link", dllink, true).append("password", link.getStringProperty("pass", null), true), new TypeRef<UnrestrictLinkResponse>(UnrestrictLinkResponse.class) {
            });

            final String genLnk = linkresp.getDownload();
            if (StringUtils.isEmpty(genLnk) || !genLnk.startsWith("http")) {
                throw new PluginException(LinkStatus.ERROR_FATAL, "Unsupported protocol");
            }
            showMessage(link, "Task 2: Download begins!");
            try {
                handleDL(account, link, genLnk, linkresp);
                return;
            } catch (PluginException e1) {
                try {
                    dl.getConnection().disconnect();
                } catch (final Throwable e) {
                }
                if (br.containsHTML("An error occurr?ed while generating a premium link, please contact an Administrator")) {
                    logger.info("Error while generating premium link, removing host from supported list");
                    tempUnavailableHoster(account, link, 60 * 60 * 1000l);
                }
                if (br.containsHTML("An error occurr?ed while attempting to download the file.")) {
                    throw new PluginException(LinkStatus.ERROR_RETRY);
                }

                throw e1;
            }
        } catch (APIException e) {
            switch (e.getError()) {
            case FILE_UNAVAILABLE:
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, _JDT.T.downloadlink_status_error_hoster_temp_unavailable(), 10 * 60 * 1000l);
            case UNSUPPORTED_HOSTER:
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unsupported Hoster: " + link.getDefaultPlugin().buildExternalDownloadURL(link, this));
            case HOSTER_TEMP_UNAVAILABLE:
            case HOSTER_IN_MAINTENANCE:
            case HOSTER_LIMIT_REACHED:
            case HOSTER_PREMIUM_ONLY:
                tempUnavailableHoster(account, link, 30 * 60 * 1000l);
                return;
            default:
                throw e;
            }

        }
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        handleFree(link);
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        return false;
    }

    private boolean isDirectLink(final DownloadLink downloadLink) {
        if (downloadLink.getPluginPatternMatcher().matches(this.getLazyP().getPatternSource())) {
            return true;
        }
        return false;
    }

    public ClientSecret checkCredentials(CodeResponse code) throws IOException, APIException {

        return callRestAPIInternal(API + "/oauth/v2/device/credentials?client_id=" + Encoding.urlEncode(CLIENT_ID) + "&code=" + Encoding.urlEncode(code.getDevice_code()), null, ClientSecret.TYPE);

    }

    private void login(Account account, boolean force) throws Exception {
        synchronized (LOCK) {

            // first try to use the stored token
            if (!force) {
                String tokenJSon = account.getStringProperty(TOKEN);
                if (StringUtils.isNotEmpty(tokenJSon)) {
                    TokenResponse token = JSonStorage.restoreFromString(tokenJSon, new TypeRef<TokenResponse>(TokenResponse.class) {
                    });
                    // ensure that the token is at elast 5 minutes valid
                    long expireTime = token.getExpires_in() * 1000 + token.getCreateTime();
                    long now = System.currentTimeMillis();
                    if ((expireTime - 5 * 60 * 1000l) > now) {

                        this.token = token;

                        return;
                    }
                }
            }

            // token invalid, forcerefresh active or token expired.
            // Try to refresh the token
            String tokenJSon = account.getStringProperty(TOKEN);
            String clientSecretJson = account.getStringProperty(CLIENT_SECRET);
            if (StringUtils.isNotEmpty(tokenJSon) && StringUtils.isNotEmpty(clientSecretJson)) {
                TokenResponse token = JSonStorage.restoreFromString(tokenJSon, TokenResponse.TYPE);
                ClientSecret clientSecret = JSonStorage.restoreFromString(clientSecretJson, ClientSecret.TYPE);

                String tokenResponseJson = br.postPage(API + "/oauth/v2/token", new QueryInfo().append(CLIENT_ID_KEY, clientSecret.getClient_id(), true).append(CLIENT_SECRET_KEY, clientSecret.getClient_secret(), true).append("code", token.getRefresh_token(), true).append("grant_type", "http://oauth.net/grant_type/device/1.0", true));
                TokenResponse newToken = JSonStorage.restoreFromString(tokenResponseJson, TokenResponse.TYPE);
                if (newToken.validate()) {
                    this.token = newToken;

                    account.setProperty(TOKEN, JSonStorage.serializeToJson(newToken));
                    return;
                }

            }

            this.token = null;
            // Could not refresh the token. login using username and password
            br.setCookiesExclusive(true);

            prepBrowser(br);
            br.clearCookies(API);
            final Browser autoSolveBr = br.cloneBrowser();
            final CodeResponse code = JSonStorage.restoreFromString(br.getPage(API + "/oauth/v2/device/code?client_id=" + CLIENT_ID + "&new_credentials=yes"), new TypeRef<CodeResponse>(CodeResponse.class) {
            });

            ensureAPIBrowser();

            final AccountLoginOAuthChallenge challenge = new AccountLoginOAuthChallenge(getHost(), null, account, code.getDirect_verification_url()) {

                private long lastValidation;

                @Override
                public Plugin getPlugin() {
                    return RealDebridCom.this;
                }

                @Override
                public void poll(SolverJob<Boolean> job) {
                    if (System.currentTimeMillis() - lastValidation >= code.getInterval() * 1000) {
                        lastValidation = System.currentTimeMillis();

                        try {
                            clientSecret = checkCredentials(code);

                            if (clientSecret != null) {
                                job.addAnswer(new AbstractResponse<Boolean>(this, ChallengeSolver.EXTERN, 100, true));
                            }
                        } catch (Throwable e) {
                            logger.log(e);
                        }
                    }

                }

                @Override
                public boolean autoSolveChallenge() {
                    try {

                        String verificationUrl = getUrl();
                        autoSolveBr.clearCookies(verificationUrl);
                        autoSolveBr.getPage(verificationUrl);
                        Form loginForm = autoSolveBr.getFormbyActionRegex("/authorize\\?.+");
                        loginForm.getInputField("p").setValue(getAccount().getPass());
                        loginForm.getInputField("u").setValue(getAccount().getUser());
                        autoSolveBr.submitForm(loginForm);
                        Form allow = autoSolveBr.getFormBySubmitvalue("Allow");
                        allow.setPreferredSubmit("Allow");
                        autoSolveBr.submitForm(allow);
                        clientSecret = checkCredentials(code);

                        if (clientSecret != null) {
                            return true;
                        }
                    } catch (Throwable e) {
                        logger.log(e);

                    }
                    return false;
                }

            };
            challenge.setTimeout(5 * 60 * 1000);

            ChallengeResponseController.getInstance().handle(challenge);

            //
            if (clientSecret == null) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "OAuth Failed", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }

            //

            String tokenResponseJson = br.postPage(API + "/oauth/v2/token", new QueryInfo().append(CLIENT_ID_KEY, clientSecret.getClient_id(), true).append(CLIENT_SECRET_KEY, clientSecret.getClient_secret(), true).append("code", code.getDevice_code(), true).append("grant_type", "http://oauth.net/grant_type/device/1.0", true));
            TokenResponse token = JSonStorage.restoreFromString(tokenResponseJson, new TypeRef<TokenResponse>(TokenResponse.class) {
            });

            if (token.validate()) {

                this.token = token;
                UserResponse user = callRestAPIInternal("https://api.real-debrid.com/rest/1.0" + "/user", null, UserResponse.TYPE);
                if (!StringUtils.equalsIgnoreCase(account.getUser(), user.getEmail()) && !StringUtils.equalsIgnoreCase(account.getUser(), user.getUsername())) {
                    this.token = null;
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "User Mismatch. You try to add the account " + account.getUser() + "\r\nBut in your browser you are logged in as " + user.getUsername() + "\r\nPlease make sure that there is no username mismatch!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.setProperty(TOKEN, JSonStorage.serializeToJson(token));
                account.setProperty(CLIENT_SECRET, JSonStorage.serializeToJson(clientSecret));
                this.token = token;
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "Unknown Error", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        }
    }

    private Browser prepBrowser(Browser prepBr) {
        // define custom browser headers and language settings.
        prepBr.getHeaders().put("Accept-Language", "en-gb, en;q=0.9");
        prepBr.setCookie(mProt + mName, "lang", "en");
        prepBr.getHeaders().put("User-Agent", "JDownloader");
        prepBr.setCustomCharset("utf-8");
        prepBr.setConnectTimeout(2 * 60 * 1000);
        prepBr.setReadTimeout(2 * 60 * 1000);
        prepBr.setFollowRedirects(true);
        prepBr.setAllowedResponseCodes(new int[] { 504 });
        return prepBr;
    }

    @Override
    public AvailableStatus requestFileInformation(DownloadLink dl) throws PluginException, IOException {
        prepBrowser(br);
        URLConnectionAdapter con = null;
        try {
            con = br.openGetConnection(dl.getDownloadURL());
            if (con.isContentDisposition() && con.isOK()) {
                if (dl.getFinalFileName() == null) {
                    dl.setFinalFileName(getFileNameFromHeader(con));
                }
                dl.setVerifiedFileSize(con.getLongContentLength());
                dl.setAvailable(true);
                return AvailableStatus.TRUE;
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
        } catch (final Throwable e) {
            return AvailableStatus.UNCHECKABLE;
        } finally {
            try {
                /* make sure we close connection */
                con.disconnect();
            } catch (final Throwable e) {
            }
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {

    }

    @CustomStorageName("RealDebridCom")
    public interface RealDebridComConfig extends PluginConfigInterface {
        @AboutConfig
        @DefaultBooleanValue(false)
        void setIgnoreServerSideChunksNum(boolean b);

        boolean isIgnoreServerSideChunksNum();

    }

    @Override
    public ConfigContainer getConfig() {
        throw new WTFException("Not implemented");
    }

    @Override
    public SubConfiguration getPluginConfig() {
        throw new WTFException("Not implemented");
    }

    @Override
    public Class<? extends ConfigInterface> getConfigInterface() {
        return RealDebridComConfig.class;
    }

    // public void setConfigElements() {
    // getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), IGNOREMAXCHUNKS, "Ignore max chunks set by
    // real-debrid.com?").setDefaultValue(false));
    // }
    private static class RealDebridConfigPanel extends PluginConfigPanelNG {
        private RealDebridComConfig cf;

        public RealDebridConfigPanel() {
            cf = PluginJsonConfig.get(RealDebridComConfig.class);

            addPair("Ignore max chunks set by real-debrid.com?", null, null, new Checkbox(cf._getStorageHandler().getKeyHandler("IgnoreServerSideChunksNum", BooleanKeyHandler.class), null));

        }

        @Override
        public void reset() {
            for (KeyHandler m : cf._getStorageHandler().getMap().values()) {

                m.setValue(m.getDefaultValue());
            }
            new EDTRunner() {

                @Override
                protected void runInEDT() {
                    updateContents();
                }
            };
        }

        @Override
        public void save() {
        }

        @Override
        public void updateContents() {
        }

    }

    @Override
    public PluginConfigPanelNG createConfigPanel() {
        if (panel == null) {
            panel = new RealDebridConfigPanel();
        }
        return panel;
    }

    private void showMessage(DownloadLink link, String message) {
        link.getLinkStatus().setStatusText(message);
    }

    private void tempUnavailableHoster(Account account, DownloadLink downloadLink, long timeout) throws PluginException {
        if (downloadLink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unable to handle this errorcode!");
        }
        synchronized (hostUnavailableMap) {
            HashMap<String, Long> unavailableMap = hostUnavailableMap.get(account);
            if (unavailableMap == null) {
                unavailableMap = new HashMap<String, Long>();
                hostUnavailableMap.put(account, unavailableMap);
            }
            /* wait 30 mins to retry this host */
            unavailableMap.put(downloadLink.getHost(), (System.currentTimeMillis() + timeout));
        }
        throw new PluginException(LinkStatus.ERROR_RETRY);
    }

}