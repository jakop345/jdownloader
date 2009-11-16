//    jDownloader - Downloadmanager
//    Copyright (C) 2009  JD-Team support@jdownloader.org
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


package jd.plugins.optional.JDAntiStandby;

import java.util.logging.Logger;
import com.sun.jna.Native;
import jd.controlling.DownloadWatchDog;

public class JDAntiStandbyThread implements Runnable {

    private boolean run = false;
    private int sleep = 5000;
    private Logger logger;
    JDAntiStandby jdAntiStandby = null;
    
    Kernel32 kernel32 = (Kernel32) Native.loadLibrary("kernel32",
            Kernel32.class);

    public JDAntiStandbyThread(Logger logger, JDAntiStandby jdAntiStandby) {
        super();
        this.logger = logger;
        this.jdAntiStandby = jdAntiStandby;
    }

    @Override
    public void run() {
        while (true) {
            try {
                if(jdAntiStandby.isStatus()){
                switch (jdAntiStandby.getPluginConfig().getIntegerProperty("CONFIG_MODE")) {
                case 0:
                    if (run) {
                        run = false;
                        logger.fine("AntiStandby Stop");
                        kernel32.SetThreadExecutionState(Kernel32.ES_CONTINUOUS);
                    }
                    break;
                case 1:
                    if (DownloadWatchDog.getInstance().getDownloadStatus() == DownloadWatchDog.STATE.RUNNING) {
                        if (!run) {                        
                            run = true;
                            logger.fine("AntiStandby Start");
                        }
                        kernel32.SetThreadExecutionState(Kernel32.ES_CONTINUOUS
                                | Kernel32.ES_SYSTEM_REQUIRED
                                | Kernel32.ES_DISPLAY_REQUIRED);
                        
                    }
                    if ((DownloadWatchDog.getInstance().getDownloadStatus() == DownloadWatchDog.STATE.NOT_RUNNING) || (DownloadWatchDog.getInstance().getDownloadStatus() == DownloadWatchDog.STATE.STOPPING)) {
                        if (run) {
                            run = false;
                            logger.fine("AntiStandby Stop");
                            kernel32.SetThreadExecutionState(Kernel32.ES_CONTINUOUS);
                        }
                    }
                    break;
                case 2:
                    if (!run) {
                        run = true;
                        logger.fine("AntiStandby Start");
                    }
                    kernel32.SetThreadExecutionState(Kernel32.ES_CONTINUOUS
                            | Kernel32.ES_SYSTEM_REQUIRED
                            | Kernel32.ES_DISPLAY_REQUIRED);
                    break;
                default:
                    logger.finest("Config error");

                }
                }
                else
                {
                    if (run) {
                        run = false;
                        logger.fine("AntiStandby Stop");
                        kernel32.SetThreadExecutionState(Kernel32.ES_CONTINUOUS);
                    }
                }
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
            } 
        }
    }

}
