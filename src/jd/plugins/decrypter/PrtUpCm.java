//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
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

package jd.plugins.decrypter;

import java.util.ArrayList;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.parser.html.Form;
import jd.parser.html.HTMLParser;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.UserAgents;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "protectup.com" }, urls = { "http://(www\\.)?protectup\\.com/(check\\.[a-z]{10}|[a-z]{10}\\-.+)\\.html" }, flags = { 0 })
public class PrtUpCm extends PluginForDecrypt {

    // DEV NOTES
    // - No https

    public PrtUpCm(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        br.getHeaders().put("User-Agent", UserAgents.stringUserAgent());
        br.setFollowRedirects(true);
        br.getPage(parameter);
        // error clauses
        if (br.containsHTML(">Not Found</h1>")) {
            logger.info("Invalid URL: " + parameter);
            return decryptedLinks;
        }
        // find correct forum, post form
        final Form getform = br.getFormbyProperty("name", "linkprotect");
        if (getform != null) {
            br.submitForm(getform);
        }
        // find tables
        final String table = br.getRegex("<table(.*?)</table>").getMatch(0);
        if (table == null) {
            throw new DecrypterException(DecrypterException.PLUGIN_DEFECT);
        }
        // find links
        final String[] links = HTMLParser.getHttpLinks(table, null);
        if (links == null || links.length == 0) {
            if (br.containsHTML("<h4>Password:</h4>")) {
                logger.info("Password protected links are not supported yet: " + parameter);
                return decryptedLinks;
            }
            logger.warning("Either invalid URL or the plugin broken : " + parameter);
            logger.warning("Please confirm via browser, and report any bugs to developement team.");
            return null;
        }
        for (String link : links) {
            decryptedLinks.add(createDownloadlink(link));
        }
        return decryptedLinks;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

}