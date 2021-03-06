package org.jdownloader.extensions.streaming.gui.bottombar;

import javax.swing.Icon;

import org.jdownloader.gui.translate._GUI;
import org.jdownloader.gui.views.components.SearchCatInterface;
import org.jdownloader.images.NewTheme;

public enum MediaArchiveSearchCategory implements SearchCatInterface {
    FILENAME(_GUI.T.searchcategory_filename(), "text", _GUI.T.searchcategory_filename_help());

    private String label;
    private String iconKey;
    private String helptext;

    private MediaArchiveSearchCategory(String searchcategory_filename, String iconKey, String helptext) {
        label = searchcategory_filename;
        this.iconKey = iconKey;
        this.helptext = helptext;
    }

    public String getLabel() {
        return label;
    }

    public Icon getIcon() {
        return NewTheme.I().getIcon(iconKey, 18);
    }

    public String getHelpText() {
        return helptext;
    }

}
