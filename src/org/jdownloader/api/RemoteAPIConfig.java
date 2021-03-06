package org.jdownloader.api;

import java.util.ArrayList;

import org.appwork.storage.config.ConfigInterface;
import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.DefaultBooleanValue;
import org.appwork.storage.config.annotations.DescriptionForConfigEntry;
import org.appwork.storage.config.annotations.RequiresRestart;

public interface RemoteAPIConfig extends ConfigInterface {

    @DefaultBooleanValue(true)
    @AboutConfig
    @RequiresRestart("A JDownloader Restart is Required")
    @DescriptionForConfigEntry("ExternInterface(Cnl2,Flashgot) will listen on 9666")
    boolean isExternInterfaceEnabled();

    void setExternInterfaceEnabled(boolean b);

    @DefaultBooleanValue(true)
    @AboutConfig
    @RequiresRestart("A JDownloader Restart is Required")
    @DescriptionForConfigEntry("ExternInterface(Cnl2,Flashgot) will listen on localhost only")
    boolean isExternInterfaceLocalhostOnly();

    void setExternInterfaceLocalhostOnly(boolean b);

    @AboutConfig
    @DescriptionForConfigEntry("ExternInterface(Cnl2,Flashgot) Authorized Websites")
    ArrayList<String> getExternInterfaceAuth();

    void setExternInterfaceAuth(ArrayList<String> auth);

    @AboutConfig
    @DefaultBooleanValue(false)
    boolean isDeprecatedApiEnabled();

    void setDeprecatedApiEnabled(boolean b);

    @DefaultBooleanValue(false)
    @AboutConfig
    @DescriptionForConfigEntry("Enable or disable the JDAnywhere API")
    boolean isJDAnywhereApiEnabled();

    void setJDAnywhereApiEnabled(boolean b);
}
