package com.sentaroh.android.SMBSync2;

/*
The MIT License (MIT)
Copyright (c) 2011-2013 Sentaroh

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
and to permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.

*/

import static com.sentaroh.android.SMBSync2.Constants.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.media.RingtoneManager;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager.WakeLock;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Vibrator;
import android.os.storage.StorageManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.content.ContextCompat;

import com.sentaroh.android.Utilities.MiscUtil;
import com.sentaroh.android.Utilities.NotifyEvent;
import com.sentaroh.android.Utilities.SafFile;
import com.sentaroh.android.Utilities.NotifyEvent.NotifyEventListener;
import com.sentaroh.android.Utilities.SafFileManager.SafFileItem;
import com.sentaroh.android.Utilities.SafFileManager;
import com.sentaroh.android.Utilities.StringUtil;
import com.sentaroh.android.Utilities.ZipFileListItem;

public class SyncThread extends Thread {

    private GlobalParameters mGp = null;

    private NotifyEvent mNotifyToService = null;

    public final static int SYNC_RETRY_INTERVAL = 30;

    class SyncThreadWorkArea {
        public GlobalParameters gp = null;

        public ArrayList<FileLastModifiedTimeEntry> currLastModifiedList = new ArrayList<FileLastModifiedTimeEntry>();
        public ArrayList<FileLastModifiedTimeEntry> newLastModifiedList = new ArrayList<FileLastModifiedTimeEntry>();

        public ArrayList<Pattern[]> dirIncludeFilterArrayList = new ArrayList<Pattern[]>();
        public ArrayList<Pattern[]> dirExcludeFilterArrayList = new ArrayList<Pattern[]>();
        public ArrayList<Pattern> dirExcludeFilterPatternList = new ArrayList<Pattern>();
        public Pattern fileFilterInclude, fileFilterExclude;
        //		public Pattern dirFilterInclude,dirFilterExclude;
        public ArrayList<Pattern> dirIncludeFilterPatternList = new ArrayList<Pattern>();

        public final boolean ALL_COPY = false;

        public long totalTransferByte = 0, totalTransferTime = 0;
        public int totalCopyCount, totalDeleteCount, totalIgnoreCount = 0, totalRetryCount = 0;

        public boolean setLastModifiedIsFunctional = true;

        public JcifsAuth masterAuth=null;
        public JcifsAuth targetAuth=null;

        public SyncUtil util = null;

        public MediaScannerConnection mediaScanner = null;

        public PrintWriter syncHistoryWriter = null;

        public int syncDifferentFileAllowableTime = 0;
        public int syncTaskRetryCount = 0;
        public int syncTaskRetryCountOriginal = 0;

        public boolean localFileLastModListModified = false;

        public int confirmCopyResult = 0, confirmDeleteResult = 0, confirmMoveResult = 0;

        public ArrayList<String> smbFileList = null;
//		public StringBuilder strBldMaster=new StringBuilder(256);
//		public StringBuilder strBldTarget=new StringBuilder(256);

        public String exception_msg_area = "";

        public String msgs_mirror_task_file_copying = null;

        public String msgs_mirror_task_file_replaced = null,
                msgs_mirror_task_file_copied = null,
                msgs_mirror_task_file_moved = null;

        public SyncTaskItem currentSTI = null;

        public ArrayList<ZipFileListItem> zipFileList = new ArrayList<ZipFileListItem>();
        public String zipFileNameEncoding = "";
        public boolean zipFileCopyBackRequired = false;
        public String zipWorkFileName = "";
    }

    private SyncThreadWorkArea mStwa = new SyncThreadWorkArea();

    public SyncThread(GlobalParameters gp, NotifyEvent ne) {
        mGp = gp;
        mNotifyToService = ne;
        mStwa.util = new SyncUtil(mGp.appContext, "SyncThread", mGp);
        mStwa.gp = mGp;

        mGp.safMgr.setDebugEnabled(mGp.settingDebugLevel > 1);
        mGp.safMgr.loadSafFileList();
        mGp.initJcifsOption();
        prepareMediaScanner();

        mStwa.msgs_mirror_task_file_copying = mStwa.gp.appContext.getString(R.string.msgs_mirror_task_file_copying);
        mStwa.msgs_mirror_task_file_replaced = gp.appContext.getString(R.string.msgs_mirror_task_file_replaced);
        mStwa.msgs_mirror_task_file_copied = gp.appContext.getString(R.string.msgs_mirror_task_file_copied);
        mStwa.msgs_mirror_task_file_moved = gp.appContext.getString(R.string.msgs_mirror_task_file_moved);

        mStwa.zipWorkFileName = gp.appContext.getCacheDir().toString() + "/zip_work_file";

        printSafDebugInfo();

        listStorageInfo();
    }

    private void printSafDebugInfo() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mGp.appContext);
        String uuid_list = prefs.getString(SafFileManager.REMOVABLE_UUID_KEY, "");

        mStwa.util.addDebugMsg(1, "I", "SafFile SafManager=" + mGp.safMgr +
                ", uuid_list=" + uuid_list);
    }

    private void listStorageInfo() {
        mStwa.util.addDebugMsg(1, "I", "Storage information begin, API=" + Build.VERSION.SDK_INT);

        listsMountPoint();

        mStwa.util.addDebugMsg(1, "I", "getExternalSdcardPath=" + mGp.safMgr.getExternalSdcardPath());
        mStwa.util.addDebugMsg(1, "I", "getSdcardDirectory=" + mGp.safMgr.getSdcardDirectory());

        File[] fl = ContextCompat.getExternalFilesDirs(mGp.appContext, null);
        if (fl != null) {
            for (File f : fl) {
                if (f != null) mStwa.util.addDebugMsg(1, "I", "ExternalFilesDirs=" + f.getPath());
            }
        }
        if (mGp.safMgr.getSdcardSafFile() != null)
            mStwa.util.addDebugMsg(1, "I", "getSdcardSafFile name=" + mGp.safMgr.getSdcardSafFile().getName());

        listSafMgrList();

        getRemovableStoragePaths(mGp.appContext, true);

        mStwa.util.addDebugMsg(1, "I", "Storage information end");

    }

    ;

    private void listSafMgrList() {
        ArrayList<SafFileItem> sfl = mGp.safMgr.getSafList();
        for (SafFileItem item : sfl) {
            String saf_name = null;
            if (item.storageRootFile != null) saf_name = item.storageRootFile.getName();
            mStwa.util.addDebugMsg(1, "I", "SafFile list uuid=" + item.storageUuid +
                    ", root=" + item.storageRootDirectory +
                    ", mounted=" + item.storageIsMounted +
                    ", isSDCARD=" + item.storageTypeSdcard +
                    ", saf=" + item.storageRootFile +
                    ", saf name=" + saf_name);
        }
    }

    ;

    private String[] getRemovableStoragePaths(Context context, boolean debug) {
        ArrayList<String> paths = new ArrayList<String>();
        try {
            StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            Method getVolumeList = sm.getClass().getDeclaredMethod("getVolumeList");
            Object[] volumeList = (Object[]) getVolumeList.invoke(sm);
            for (Object volume : volumeList) {
//	            Method getPath = volume.getClass().getDeclaredMethod("getPath");
//	            Method isRemovable = volume.getClass().getDeclaredMethod("isRemovable");
//	            Method getUuid = volume.getClass().getDeclaredMethod("getUuid");
                Method toString = volume.getClass().getDeclaredMethod("toString");
//	            String path = (String)getPath.invoke(volume);
//	            boolean removable = (Boolean)isRemovable.invoke(volume);
                mStwa.util.addDebugMsg(1, "I", (String) toString.invoke(volume));
//	            if ((String)getUuid.invoke(volume)!=null) {
//	            	paths.add(path);
//					if (debug) {
////						Log.v(APPLICATION_TAG, "RemovableStorages Uuid="+(String)getUuid.invoke(volume)+", removable="+removable+", path="+path);
//						mStwa.util.addDebugMsg(1, "I", (String)toString.invoke(volume));
//					}
//	            }
            }
        } catch (ClassCastException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return paths.toArray(new String[paths.size()]);
    }

    ;

    private void listsMountPoint() {
        mStwa.util.addDebugMsg(1, "I", "/ directory:");
        File[] fl = (new File("/")).listFiles();
        if (fl != null) {
            for (File item : fl) {
                if (item.isDirectory())
                    mStwa.util.addDebugMsg(1, "I", "   /" + item.getName() + ", read=" + item.canRead());
            }
        }

        mStwa.util.addDebugMsg(1, "I", "/mnt directory:");
        fl = (new File("/mnt")).listFiles();
        if (fl != null) {
            for (File item : fl) {
                if (item.isDirectory())
                    mStwa.util.addDebugMsg(1, "I", "   /mnt/" + item.getName() + ", read=" + item.canRead());
            }
        }

        mStwa.util.addDebugMsg(1, "I", "/storage directory:");
        fl = (new File("/storage")).listFiles();
        if (fl != null) {
            for (File item : fl) {
                if (item.isDirectory())
                    mStwa.util.addDebugMsg(1, "I", "   /storage/" + item.getName() + ", read=" + item.canRead());
            }
        }

        mStwa.util.addDebugMsg(1, "I", "/storage/emulated directory:");
        fl = (new File("/storage/emulated")).listFiles();
        if (fl != null) {
            for (File item : fl) {
                if (item.isDirectory())
                    mStwa.util.addDebugMsg(1, "I", "   /storage/emulated/" + item.getName() + ", read=" + item.canRead());
            }
        }

        mStwa.util.addDebugMsg(1, "I", "/storage/self directory:");
        fl = (new File("/storage/self")).listFiles();
        if (fl != null) {
            for (File item : fl) {
                if (item.isDirectory())
                    mStwa.util.addDebugMsg(1, "I", "   /storage/self/" + item.getName() + ", read=" + item.canRead());
            }
        }

        mStwa.util.addDebugMsg(1, "I", "/Removable directory:");
        fl = (new File("/Removable")).listFiles();
        if (fl != null) {
            for (File item : fl) {
                if (item.isDirectory())
                    mStwa.util.addDebugMsg(1, "I", "   /Removable/" + item.getName() + ", read=" + item.canRead());
            }
        }
    }

    ;

    @Override
    public void run() {
        if (!mGp.syncThreadActive) {
            defaultUEH = Thread.currentThread().getUncaughtExceptionHandler();
            Thread.currentThread().setUncaughtExceptionHandler(unCaughtExceptionHandler);

            mGp.syncThreadActive = true;
//			showMsg(stwa,false, "","I","","",mGp.appContext.getString(R.string.msgs_mirror_task_started));
            NotificationUtil.setNotificationIcon(mGp, R.drawable.ic_48_smbsync_run_anim, R.drawable.ic_48_smbsync_run);

            loadLocalFileLastModList();

            waitMediaScannerConnected();

            mGp.syncThreadControl.initThreadCtrl();

            SyncRequestItem sri = mGp.syncRequestQueue.poll();
            boolean sync_error_detected = false;
            int sync_result = 0;
            boolean wifi_off_after_end = false;

            reconnectWifi();

            while (sri != null && sync_result == 0) {
                mStwa.util.addLogMsg("I",
                        String.format(mGp.appContext.getString(R.string.msgs_mirror_sync_request_started),
                                sri.request_id));
                mGp.syncThreadRequestID = sri.request_id;
                mStwa.util.addDebugMsg(1, "I", "Sync request option : Requestor=" + mGp.syncThreadRequestID +
                        ", WiFi on=" + sri.wifi_on_before_sync_start +
                        ", WiFi delay=" + sri.start_delay_time_after_wifi_on + ", WiFi off=" + sri.wifi_off_after_sync_ended);

                if (sri.wifi_on_before_sync_start) {
                    if (!isWifiOn()) {
                        setWifiOn();
                        if (sri.start_delay_time_after_wifi_on > 0) {
                            mStwa.util.addLogMsg("I",
                                    String.format(mGp.appContext.getString(R.string.msgs_mirror_sync_start_was_delayed),
                                            sri.start_delay_time_after_wifi_on));
                            SystemClock.sleep(1000 * sri.start_delay_time_after_wifi_on);
                        }
                    }
                }

                mStwa.currentSTI = sri.sync_task_list.poll();

                long start_time = 0;
                while ((sync_result == 0 || sync_result == SyncTaskItem.SYNC_STATUS_WARNING) && mStwa.currentSTI != null) {
                    start_time = System.currentTimeMillis();
                    listSyncOption(mStwa.currentSTI);
                    setSyncTaskRunning(true);
                    showMsg(mStwa, false, mStwa.currentSTI.getSyncTaskName(), "I", "", "",
                            mGp.appContext.getString(R.string.msgs_mirror_task_started));

                    String mst_dom=null, mst_user=null, mst_pass=null;
                    mst_dom=mStwa.currentSTI.getMasterSmbDomain().equals("")?null:mStwa.currentSTI.getMasterSmbDomain();
                    mst_user=mStwa.currentSTI.getMasterSmbUserName().equals("")?null:mStwa.currentSTI.getMasterSmbUserName();
                    mst_pass=mStwa.currentSTI.getMasterSmbPassword().equals("")?null:mStwa.currentSTI.getMasterSmbPassword();
                    if (mStwa.currentSTI.getMasterSmbProtocol().equals(SyncTaskItem.SYNC_FOLDER_SMB_PROTOCOL_SMB1_ONLY)) {
                        mStwa.masterAuth=new JcifsAuth(true, mst_dom, mst_user, mst_pass);
                    } else {
                        mStwa.masterAuth=new JcifsAuth(false, mst_dom, mst_user, mst_pass);
                    }

                    String tgt_dom=null, tgt_user=null, tgt_pass=null;
                    tgt_dom=mStwa.currentSTI.getTargetSmbDomain().equals("")?null:mStwa.currentSTI.getTargetSmbDomain();
                    tgt_user=mStwa.currentSTI.getTargetSmbUserName().equals("")?null:mStwa.currentSTI.getTargetSmbUserName();
                    tgt_pass=mStwa.currentSTI.getTargetSmbPassword().equals("")?null:mStwa.currentSTI.getTargetSmbPassword();
                    if (mStwa.currentSTI.getTargetSmbProtocol().equals(SyncTaskItem.SYNC_FOLDER_SMB_PROTOCOL_SMB1_ONLY)) {
                        mStwa.targetAuth=new JcifsAuth(true, tgt_dom, tgt_user, tgt_pass);
                    } else {
                        mStwa.targetAuth=new JcifsAuth(false, tgt_dom, tgt_user, tgt_pass);
                    }

                    initSyncParms(mStwa.currentSTI);

                    String wifi_msg = isWifiConditionSatisfied(mStwa.currentSTI);
                    if (wifi_msg.equals("")) {
                        if ((mStwa.currentSTI.isSyncOptionSyncWhenCharging() && SyncUtil.isCharging(mGp.appContext)) ||
                                !mStwa.currentSTI.isSyncOptionSyncWhenCharging()) {
                            compileFilter(mStwa.currentSTI, mStwa.currentSTI.getFileFilter(), mStwa.currentSTI.getDirFilter());
                            sync_result = checkStorageAccess(mStwa.currentSTI);

                            if (sync_result == SyncTaskItem.SYNC_STATUS_SUCCESS)
                                sync_result = performSync(mStwa.currentSTI);
                        } else {
                            sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                            String be = mGp.appContext.getString(R.string.msgs_mirror_sync_cancelled_battery_option_not_satisfied);
                            showMsg(mStwa, true, mStwa.currentSTI.getSyncTaskName(), "E", "", "", be);
                            mGp.syncThreadControl.setThreadMessage(be);
                        }
                    } else {
                        if (wifi_msg.equals(mGp.appContext.getString(R.string.msgs_mirror_sync_skipped_wifi_ap_conn_other))) {
//                                sync_result=SyncTaskItem.SYNC_STATUS_SUCCESS;
                            sync_result = SyncTaskItem.SYNC_STATUS_WARNING;
                            showMsg(mStwa, true, mStwa.currentSTI.getSyncTaskName(), "W", "", "", wifi_msg);
                            mGp.syncThreadControl.setThreadMessage(wifi_msg);
                        } else {
                            sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                            showMsg(mStwa, true, mStwa.currentSTI.getSyncTaskName(), "E", "", "", wifi_msg);
                            mGp.syncThreadControl.setThreadMessage(wifi_msg);
                        }
                    }

                    saveLocalFileLastModList();

                    postProcessSyncResult(mStwa.currentSTI, sync_result, (System.currentTimeMillis() - start_time));

                    mStwa.currentSTI = sri.sync_task_list.poll();
                    if ((mStwa.currentSTI != null || mGp.syncRequestQueue.size() > 0) &&
                            mGp.settingErrorOption && sync_result == SyncHistoryItem.SYNC_STATUS_ERROR) {
                        showMsg(mStwa, false, mStwa.currentSTI.getSyncTaskName(), "W", "", "",
                                mGp.appContext.getString(R.string.msgs_mirror_task_result_error_skipped));
                        sync_error_detected = true;
                        sync_result = SyncTaskItem.SYNC_STATUS_SUCCESS;
                    }
                }

                if (sri.wifi_off_after_sync_ended) wifi_off_after_end = true;

                mStwa.util.addLogMsg("I",
                        String.format(mGp.appContext.getString(R.string.msgs_mirror_sync_request_ended), sri.request_id));
                sri = mGp.syncRequestQueue.poll();
            }

            if (wifi_off_after_end) if (isWifiOn()) setWifiOff();

            if (sync_error_detected) {
                showMsg(mStwa, false, "", "W", "", "",
                        mGp.appContext.getString(R.string.msgs_mirror_task_sync_request_error_detected));
            }

            saveLocalFileLastModList();

            NotificationUtil.setNotificationIcon(mGp, R.drawable.ic_48_smbsync_wait, R.drawable.ic_48_smbsync_wait);
            NotificationUtil.reShowOngoingMsg(mGp);
            @SuppressWarnings("unused")
            boolean delay_term = false;
            if (sync_result == SyncTaskItem.SYNC_STATUS_SUCCESS) {
                if (mGp.settingRingtoneWhenSyncEnded.equals(SMBSYNC2_RINGTONE_NOTIFICATION_ALWAYS) ||
                        mGp.settingRingtoneWhenSyncEnded.equals(SMBSYNC2_RINGTONE_NOTIFICATION_SUCCESS)) {
                    playBackDefaultNotification();
                    delay_term = true;
                }
                if (mGp.settingVibrateWhenSyncEnded.equals(SMBSYNC2_VIBRATE_WHEN_SYNC_ENDED_ALWAYS) ||
                        mGp.settingVibrateWhenSyncEnded.equals(SMBSYNC2_VIBRATE_WHEN_SYNC_ENDED_SUCCESS)) {
                    vibrateDefaultPattern();
                    delay_term = true;
                }
            } else if (sync_result == SyncTaskItem.SYNC_STATUS_CANCEL) {
                if (mGp.settingRingtoneWhenSyncEnded.equals(SMBSYNC2_RINGTONE_NOTIFICATION_ALWAYS)) {
                    playBackDefaultNotification();
                    delay_term = true;
                }
                if (mGp.settingVibrateWhenSyncEnded.equals(SMBSYNC2_VIBRATE_WHEN_SYNC_ENDED_ALWAYS)) {
                    vibrateDefaultPattern();
                    delay_term = true;
                }
            } else if (sync_result == SyncTaskItem.SYNC_STATUS_ERROR) {
                if (mGp.settingRingtoneWhenSyncEnded.equals(SMBSYNC2_RINGTONE_NOTIFICATION_ALWAYS) ||
                        mGp.settingRingtoneWhenSyncEnded.equals(SMBSYNC2_RINGTONE_NOTIFICATION_ERROR)) {
                    playBackDefaultNotification();
                    delay_term = true;
                }
                if (mGp.settingVibrateWhenSyncEnded.equals(SMBSYNC2_VIBRATE_WHEN_SYNC_ENDED_ALWAYS) ||
                        mGp.settingVibrateWhenSyncEnded.equals(SMBSYNC2_VIBRATE_WHEN_SYNC_ENDED_ERROR)) {
                    vibrateDefaultPattern();
                    delay_term = true;
                }
            }
            mGp.syncThreadRequestID = "";
            mGp.syncThreadActive = false;

            mStwa.mediaScanner.disconnect();


//			if (delay_term && mGp.callbackStub==null) SystemClock.sleep(1000);

//	        Thread.currentThread().setUncaughtExceptionHandler(defaultUEH);

            mNotifyToService.notifyToListener(true, new Object[]{sync_result});
        }
        System.gc();
    }

    private void setSyncTaskRunning(boolean running) {
        SyncTaskItem c_sti = SyncTaskUtil.getSyncTaskByName(mGp.syncTaskList, mStwa.currentSTI.getSyncTaskName());

        c_sti.setSyncTaskRunning(running);

        if (running) openSyncResultLog(c_sti);
        else closeSyncResultLog();

        refreshSyncTaskListAdapter();
    }

    ;

    private void listSyncOption(SyncTaskItem sti) {
        mStwa.util.addDebugMsg(1, "I", "Sync Task : Type=" + sti.getSyncTaskType());
        mStwa.util.addDebugMsg(1, "I", "   Master Type=" + sti.getMasterFolderType() +
                ", Addr=" + sti.getMasterSmbAddr() +
                ", Hostname=" + sti.getMasterSmbHostName() +
                ", Port=" + sti.getMasterSmbPort() +
                ", SmbShare=" + sti.getMasterRemoteSmbShareName() +
                ", UserID=" + sti.getMasterSmbUserName() +
                ", Directory=" + sti.getMasterDirectoryName() +
                ", RemovableID=" + sti.getMasterRemovableStorageID() +
                "");
        mStwa.util.addDebugMsg(1, "I", "   Target Type=" + sti.getTargetFolderType() +
                ", Addr=" + sti.getTargetSmbAddr() +
                ", Hostname=" + sti.getTargetSmbHostName() +
                ", Port=" + sti.getTargetSmbPort() +
                ", SmbShare=" + sti.getTargetSmbShareName() +
                ", UserID=" + sti.getTargetSmbUserName() +
                ", Directory=" + sti.getTargetDirectoryName() +
                ", RemovableID=" + sti.getTargetRemovableStorageID() +
                "");
        mStwa.util.addDebugMsg(1, "I", "   File filter Audio=" + sti.isSyncFileTypeAudio() +
                ", Image=" + sti.isSyncFileTypeImage() +
                ", Video=" + sti.isSyncFileTypeVideo() +
                "");
        mStwa.util.addDebugMsg(1, "I", "   Confirm=" + sti.isSyncConfirmOverrideOrDelete() +
                ", LastModSmbsync2=" + sti.isSyncDetectLastModifiedBySmbsync() +
                ", UseLastMod=" + sti.isSyncDifferentFileByTime() +
                ", USeFileSize=" + sti.isSyncDifferentFileBySize() +
                ", DoNotResetRemote=" + sti.isSyncDoNotResetLastModifiedSmbFile() +
                ", SyncEmptyDir=" + sti.isSyncEmptyDirectory() +
                ", SyncHiddenDir=" + sti.isSyncHiddenDirectory() +
                ", SyncProcessOverride=" + sti.isSyncOverrideCopyMoveFile() +
                ", ProcessRootDirFile=" + sti.isSyncProcessRootDirFile() +
                ", SyncSubDir=" + sti.isSyncSubDirectory() +
                ", AutoSync=" + sti.isSyncTaskAuto() +
                ", TestMode=" + sti.isSyncTestMode() +
                ", UseTempName=" + sti.isSyncUseFileCopyByTempName() +
                ", UseSmallBuffer=" + sti.isSyncUseSmallIoBuffer() +
                ", AllowableTime=" + sti.getSyncDifferentFileAllowableTime() +
                ", RetryCount=" + sti.getSyncRetryCount() +
                ", UseExtendedDirectoryFilter1=" + sti.isSyncUseExtendedDirectoryFilter1() +
                ", SkipIfConnectAnotherWifiSsid=" + sti.isSyncTaskSkipIfConnectAnotherWifiSsid() +
                ", SyncOnlyCharging=" + sti.isSyncOptionSyncWhenCharging() +
                "");
    }

    ;

    private void initSyncParms(SyncTaskItem sti) {
        mStwa.syncTaskRetryCount = mStwa.syncTaskRetryCountOriginal = Integer.parseInt(sti.getSyncRetryCount()) + 1;
        mStwa.syncDifferentFileAllowableTime = sti.getSyncDifferentFileAllowableTime() * 1000;

        mStwa.totalTransferByte = mStwa.totalTransferTime = 0;
        mStwa.totalCopyCount = mStwa.totalDeleteCount = mStwa.totalIgnoreCount = mStwa.totalRetryCount = 0;

        if (sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_INTERNAL)) {
            if (sti.isSyncDetectLastModifiedBySmbsync()) mStwa.setLastModifiedIsFunctional = false;
            else
                mStwa.setLastModifiedIsFunctional = isSetLastModifiedFunctional(mStwa.gp.internalRootDirectory);
//		} else if (sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_USB)) {
//			mStwa.setLastModifiedIsFunctional=false;
        } else if (sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SMB)) {
            mStwa.setLastModifiedIsFunctional = true;
        } else mStwa.setLastModifiedIsFunctional = false;//SDCARD
        mStwa.util.addDebugMsg(1, "I", "setLastModifiedIsFunctional=" + mStwa.setLastModifiedIsFunctional);
    }

    ;

    private void postProcessSyncResult(SyncTaskItem sti, int sync_result, long et) {
        int t_et_sec = (int) (et / 1000);
        int t_et_ms = (int) (et - (t_et_sec * 1000));

        String sync_et = String.valueOf(t_et_sec) + "." + String.format("%3d", t_et_ms).replaceAll(" ", "0");

        String error_msg = "";
        if (sync_result == SyncTaskItem.SYNC_STATUS_ERROR || sync_result == SyncTaskItem.SYNC_STATUS_WARNING) {
            error_msg = mGp.syncThreadControl.getThreadMessage();
        }
//		if (!error_msg.equals("")) {
//			if (mStwa.syncHistoryWriter!=null) {
//				String print_msg="";
//				print_msg=mStwa.util.buildPrintMsg("E", sti.getSyncTaskName()," ",error_msg);
//				mStwa.syncHistoryWriter.println(print_msg);
//			}
//		}
        addHistoryList(sti, sync_result,
                mStwa.totalCopyCount, mStwa.totalDeleteCount, mStwa.totalIgnoreCount, mStwa.totalRetryCount,
                error_msg, sync_et);
//		if (!error_msg.equals("")) showMsg(mStca, false,sti.getSyncTaskName(),"E", "","",error_msg);

        showMsg(mStwa, true, sti.getSyncTaskName(), "I", "", "",
                String.format(mGp.appContext.getString(R.string.msgs_mirror_task_no_of_copy),
                        mStwa.totalCopyCount, mStwa.totalDeleteCount, mStwa.totalIgnoreCount, sync_et));
        showMsg(mStwa, true, sti.getSyncTaskName(), "I", "", "",
                String.format(mGp.appContext.getString(R.string.msgs_mirror_task_avg_rate),
                        calTransferRate(mStwa.totalTransferByte, mStwa.totalTransferTime)));

        if (sync_result == SyncTaskItem.SYNC_STATUS_SUCCESS) {
            showMsg(mStwa, false, sti.getSyncTaskName(), "I", "", "", mGp.appContext.getString(R.string.msgs_mirror_task_result_ok));
        } else if (sync_result == SyncTaskItem.SYNC_STATUS_WARNING) {
            showMsg(mStwa, false, sti.getSyncTaskName(), "I", "", "", mGp.appContext.getString(R.string.msgs_mirror_task_result_ok));
        } else if (sync_result == SyncTaskItem.SYNC_STATUS_CANCEL) {
            showMsg(mStwa, false, sti.getSyncTaskName(), "I", "", "", mGp.appContext.getString(R.string.msgs_mirror_task_result_cancel));
        } else if (sync_result == SyncTaskItem.SYNC_STATUS_ERROR) {
            showMsg(mStwa, false, sti.getSyncTaskName(), "E", "", "",
                    mGp.appContext.getString(R.string.msgs_mirror_task_result_error_ended));
        }

        setSyncTaskRunning(false);
        SyncTaskUtil.saveSyncTaskListToFile(mGp, mGp.appContext, mStwa.util, false, "", "", mGp.syncTaskList, false);

    }

    ;

    private void loadLocalFileLastModList() {
        mStwa.localFileLastModListModified = false;
        NotifyEvent ntfy = new NotifyEvent(mGp.appContext);
        ntfy.setListener(new NotifyEventListener() {
            @Override
            public void positiveResponse(Context c, Object[] o) {
            }

            @Override
            public void negativeResponse(Context c, Object[] o) {
                String en = (String) o[0];
                mStwa.util.addLogMsg("W", "Duplicate local file last modified entry was ignored, name=" + en);
            }
        });
        FileLastModifiedTime.loadLastModifiedList(mGp.settingMgtFileDir, mStwa.currLastModifiedList, mStwa.newLastModifiedList, ntfy);
    }

    ;

    private void saveLocalFileLastModList() {
        if (mStwa.localFileLastModListModified) {
            long b_time = System.currentTimeMillis();
            mStwa.localFileLastModListModified = false;
            FileLastModifiedTime.saveLastModifiedList(mGp.settingMgtFileDir, mStwa.currLastModifiedList, mStwa.newLastModifiedList);
            mStwa.util.addDebugMsg(1, "I", "saveLastModifiedList elapsed time=" + (System.currentTimeMillis() - b_time));
        }
    }

    ;

    private int checkStorageAccess(SyncTaskItem sti) {
        int sync_result = 0;
        if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SDCARD) ||
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SDCARD)) {
            if (mGp.safMgr.getSdcardDirectory().equals(SafFileManager.UNKNOWN_SDCARD_DIRECTORY)) {
                sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                String e_msg = "";
                if (SafFileManager.isSdcardMountPointExisted(mGp.appContext, mGp.settingDebugLevel > 0)) {
                    e_msg = mGp.appContext.getString(R.string.msgs_mirror_external_sdcard_select_required);
                } else {
                    e_msg = mGp.appContext.getString(R.string.msgs_mirror_external_sdcard_not_mounted);
                }
                showMsg(mStwa, true, sti.getSyncTaskName(), "E", "", "", e_msg);
                mGp.syncThreadControl.setThreadMessage(e_msg);
                return sync_result;
            } else if (mGp.safMgr.getSdcardSafFile() == null) {
                sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                showMsg(mStwa, true, sti.getSyncTaskName(), "E", "", "",
                        mGp.appContext.getString(R.string.msgs_mirror_external_sdcard_select_required));
                mGp.syncThreadControl.setThreadMessage(
                        mGp.appContext.getString(R.string.msgs_mirror_external_sdcard_select_required));
                return sync_result;
            }
        }
        if (sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_ZIP) &&
                sti.isTargetZipUseExternalSdcard()) {
            if (mGp.safMgr.getSdcardDirectory().equals(SafFileManager.UNKNOWN_SDCARD_DIRECTORY)) {
                sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                String e_msg = "";
                if (SafFileManager.isSdcardMountPointExisted(mGp.appContext, mGp.settingDebugLevel > 0)) {
                    e_msg = mGp.appContext.getString(R.string.msgs_mirror_external_sdcard_select_required);
                } else {
                    e_msg = mGp.appContext.getString(R.string.msgs_mirror_external_sdcard_not_mounted);
                }
                showMsg(mStwa, true, sti.getSyncTaskName(), "E", "", "", e_msg);
                mGp.syncThreadControl.setThreadMessage(e_msg);
                return sync_result;
            } else if (mGp.safMgr.getSdcardSafFile() == null) {
                sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                showMsg(mStwa, true, sti.getSyncTaskName(), "E", "", "",
                        mGp.appContext.getString(R.string.msgs_mirror_external_sdcard_select_required));
                mGp.syncThreadControl.setThreadMessage(
                        mGp.appContext.getString(R.string.msgs_mirror_external_sdcard_select_required));
                return sync_result;
            }
        }

        if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SMB)) {
            String addr = sti.getMasterSmbAddr();
            if (!sti.getMasterSmbHostName().equals("")) {
                addr = resolveHostName(mStwa.masterAuth.isSmb1Auth(), sti.getMasterSmbHostName());
                if (addr == null) {
                    String msg = mGp.appContext.getString(R.string.msgs_mirror_remote_name_not_found) +
                            sti.getMasterSmbHostName();
                    mStwa.util.addLogMsg("E", "", msg);
                    mGp.syncThreadControl.setThreadMessage(msg);
                    sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                    return sync_result;
                }
            }
            if (sti.getMasterSmbPort().equals("")) {
//				if (!SmbUtil.isIpAddressAndPortConnected(addr,139,3500)
//						&& !SmbUtil.isIpAddressAndPortConnected(addr,445,3500)
//					) {
                if (!isIpaddressConnectable(addr, 139)
                        && !isIpaddressConnectable(addr, 445)
                        ) {
                    sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                    showMsg(mStwa, true, sti.getSyncTaskName(), "E", "", "",
                            String.format(mGp.appContext.getString(R.string.msgs_mirror_remote_addr_not_connected), addr));
                    mGp.syncThreadControl.setThreadMessage(
                            String.format(mGp.appContext.getString(R.string.msgs_mirror_remote_addr_not_connected), addr));
                    return sync_result;
                }
            } else {
                int port = Integer.parseInt(sti.getMasterSmbPort());
//				if (!SmbUtil.isIpAddressAndPortConnected(addr,port,3500)) {
                if (!isIpaddressConnectable(addr, port)) {
                    sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                    showMsg(mStwa, true, sti.getSyncTaskName(), "E", "", "",
                            String.format(mGp.appContext.getString(R.string.msgs_mirror_remote_addr_not_connected_with_port), addr, port));
                    mGp.syncThreadControl.setThreadMessage(
                            String.format(mGp.appContext.getString(R.string.msgs_mirror_remote_addr_not_connected_with_port), addr, port));
                    return sync_result;
                }
            }
        }
        if (sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SMB)) {
            String addr = sti.getTargetSmbAddr();
            if (!sti.getTargetSmbHostName().equals("")) {
                addr = resolveHostName(mStwa.targetAuth.isSmb1Auth(), sti.getTargetSmbHostName());
                if (addr == null) {
                    String msg = mGp.appContext.getString(R.string.msgs_mirror_remote_name_not_found) +
                            sti.getTargetSmbHostName();
                    mStwa.util.addLogMsg("E", "", msg);
                    mGp.syncThreadControl.setThreadMessage(msg);
                    sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                    return sync_result;
                }
            }
            if (sti.getTargetSmbPort().equals("")) {
//				if (!SmbUtil.isIpAddressAndPortConnected(addr,139,3500) &&
//						!SmbUtil.isIpAddressAndPortConnected(addr,445,3500)) {
                if (!isIpaddressConnectable(addr, 139) &&
                        !isIpaddressConnectable(addr, 445)) {
                    sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                    showMsg(mStwa, true, sti.getSyncTaskName(), "E", "", "",
                            String.format(mGp.appContext.getString(R.string.msgs_mirror_remote_addr_not_connected), addr));
                    mGp.syncThreadControl.setThreadMessage(
                            String.format(mGp.appContext.getString(R.string.msgs_mirror_remote_addr_not_connected), addr));
                    return sync_result;
                }
            } else {
                int port = Integer.parseInt(sti.getTargetSmbPort());
//				if (!SmbUtil.isIpAddressAndPortConnected(addr,port,3500)) {
                if (!isIpaddressConnectable(addr, port)) {
                    sync_result = SyncTaskItem.SYNC_STATUS_ERROR;
                    showMsg(mStwa, true, sti.getSyncTaskName(), "E", "", "",
                            String.format(mGp.appContext.getString(R.string.msgs_mirror_remote_addr_not_connected_with_port), addr, port));
                    mGp.syncThreadControl.setThreadMessage(
                            String.format(mGp.appContext.getString(R.string.msgs_mirror_remote_addr_not_connected_with_port), addr, port));
                    return sync_result;
                }
            }
        }

        return sync_result;
    }

    ;

    private boolean isIpaddressConnectable(String addr, int port) {
        int cnt = 7;
        boolean result = false;
        while (cnt > 0) {
            result = isIpAddressAndPortConnected(addr, port, 1000);
            if (result) break;
            cnt--;
        }
        return result;
    }

    ;

    final public boolean isIpAddressAndPortConnected(String address, int port, int timeout) {
        boolean reachable = false;
        Socket socket = new Socket();
        try {
            socket.bind(null);
            socket.connect((new InetSocketAddress(address, port)), timeout);
//            OutputStream os=socket.getOutputStream();
//            os.write(mNbtData);
//            os.flush();
//            os.close();
            reachable = true;
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
            mStwa.util.addDebugMsg(1, "I", e.getMessage());
            StackTraceElement[] ste = e.getStackTrace();
            for (int i = 0; i < ste.length; i++) {
                mStwa.util.addDebugMsg(1, "I", ste[i].toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
            mStwa.util.addDebugMsg(1, "I", e.getMessage());
            StackTraceElement[] ste = e.getStackTrace();
            for (int i = 0; i < ste.length; i++) {
                mStwa.util.addDebugMsg(1, "I", ste[i].toString());
            }
        }
        return reachable;
    }

    ;

    private String resolveHostName(boolean smb1, String hn) {
        String ipAddress = JcifsUtil.getSmbHostIpAddressFromName(smb1, hn);
        if (ipAddress == null) {//add dns name resolve
            try {
                InetAddress[] addr_list = Inet4Address.getAllByName(hn);
                for (InetAddress item : addr_list) {
//					Log.v("","addr="+item.getHostAddress()+", l="+item.getAddress().length);
                    if (item.getAddress().length == 4) {
                        ipAddress = item.getHostAddress();
                    }
                }
            } catch (UnknownHostException e) {
//				e.printStackTrace();
            }
        }

        mStwa.util.addDebugMsg(1, "I", "resolveHostName Name=" + hn + ", IP addr=" + ipAddress);
        return ipAddress;
    }


    // Default uncaught exception handler variable
    private UncaughtExceptionHandler defaultUEH;

    private Thread.UncaughtExceptionHandler unCaughtExceptionHandler =
            new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable ex) {
                    Thread.currentThread().setUncaughtExceptionHandler(defaultUEH);
                    NotificationUtil.setNotificationIcon(mGp, R.drawable.ic_48_smbsync_wait, R.drawable.ic_48_smbsync_wait);
                    ex.printStackTrace();
                    StackTraceElement[] st = ex.getStackTrace();
                    String st_msg = "";
                    for (int i = 0; i < st.length; i++) {
                        st_msg += "\n at " + st[i].getClassName() + "." +
                                st[i].getMethodName() + "(" + st[i].getFileName() +
                                ":" + st[i].getLineNumber() + ")";
                    }
                    mGp.syncThreadControl.setThreadResultError();
                    String end_msg = ex.toString() + st_msg;
                    if (mStwa.gp.safMgr != null) {
                        if (!mStwa.gp.safMgr.getSafDebugMsg().equals(""))
                            end_msg += "\n" + mStwa.gp.safMgr.getSafDebugMsg();

                        end_msg += "\n" + "getExternalSdcardPath=" + mGp.safMgr.getExternalSdcardPath() + ", getSdcardDirectory=" + mGp.safMgr.getSdcardDirectory();

                        File[] fl = ContextCompat.getExternalFilesDirs(mGp.appContext, null);
                        if (fl != null) {
                            for (File f : fl) {
                                if (f != null) end_msg += "\n" + "ExternalFilesDirs=" + f.getPath();
                            }
                        }
                        if (mGp.safMgr.getSdcardSafFile() != null)
                            end_msg += "\n" + "getSdcardSafFile name=" + mGp.safMgr.getSdcardSafFile().getName();

                        ArrayList<SafFileItem> sfl = mGp.safMgr.getSafList();
                        for (SafFileItem item : sfl) {
                            end_msg += "\n" + "SafFile list uuid=" + item.storageUuid +
                                    ", root=" + item.storageRootDirectory +
                                    ", mounted=" + item.storageIsMounted +
                                    ", isSDCARD=" + item.storageTypeSdcard +
                                    ", saf=" + item.storageRootFile;
                        }
                    }

                    mGp.syncThreadControl.setThreadMessage(end_msg);
                    showMsg(mStwa, true, "", "E", "", "", end_msg);
                    showMsg(mStwa, false, "", "E", "", "",
                            mGp.appContext.getString(R.string.msgs_mirror_task_result_error_ended));

                    if (mStwa.currentSTI != null) {
                        addHistoryList(mStwa.currentSTI, SyncHistoryItem.SYNC_STATUS_ERROR,
                                mStwa.totalCopyCount, mStwa.totalDeleteCount, mStwa.totalIgnoreCount, mStwa.totalRetryCount,
                                end_msg, "");
//        			mUtil.saveHistoryList(mGp.syncHistoryList);
                        setSyncTaskRunning(false);
                    }
                    mGp.syncThreadControl.setDisabled();
                    mGp.syncThreadRequestID = "";

                    mGp.syncThreadActive = false;
                    mGp.dialogWindowShowed = false;
                    mGp.syncRequestQueue.clear();

                    mNotifyToService.notifyToListener(false, null);
                    // re-throw critical exception further to the os (important)
//                defaultUEH.uncaughtException(thread, ex);
                }
            };

    private void refreshSyncTaskListAdapter() {
        mGp.uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mGp.syncTaskAdapter != null) {
                    int run_task = -1;
                    for (int i = 0; i < mGp.syncTaskList.size(); i++)
                        if (mGp.syncTaskList.get(i).isSyncTaskRunning()) run_task = i;
                    mGp.syncTaskAdapter.notifyDataSetChanged();
                    mGp.syncTaskListView.setSelection(run_task);
                }
            }
        });
    }

    ;

    private int performSync(SyncTaskItem sti) {
        int sync_result = 0;
        long time_millis = System.currentTimeMillis();
        String from, to, to_temp;
        if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_INTERNAL) &&
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_INTERNAL)) {
            from = buildStorageDir(sti.getMasterLocalMountPoint(), sti.getMasterDirectoryName());
            to_temp = buildStorageDir(sti.getTargetLocalMountPoint(), sti.getTargetDirectoryName());

            to = replaceKeywordValue(to_temp, time_millis);

            mStwa.util.addDebugMsg(1, "I", "Sync Internal-To-Internal From=" + from + ", To=" + to);

            if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_COPY)) {
                sync_result = SyncThreadSyncFile.syncCopyInternalToInternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MOVE)) {
                sync_result = SyncThreadSyncFile.syncMoveInternalToInternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MIRROR)) {
                sync_result = SyncThreadSyncFile.syncMirrorInternalToInternal(mStwa, sti, from, to);
            }
        } else if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_INTERNAL) &&
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_ZIP)) {
            from = buildStorageDir(sti.getMasterLocalMountPoint(), sti.getMasterDirectoryName());
            to_temp = sti.getTargetLocalMountPoint() + sti.getTargetZipOutputFileName();

            to = replaceKeywordValue(to_temp, time_millis);

            mStwa.util.addDebugMsg(1, "I", "Sync Internal-To-ZIP From=" + from + ", To=" + to);

            if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_COPY)) {
                sync_result = SyncThreadSyncZip.syncCopyInternalToInternalZip(mStwa, sti, from, replaceKeywordValue(sti.getTargetZipOutputFileName(), time_millis));
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MOVE)) {
                sync_result = SyncThreadSyncZip.syncMoveInternalToInternalZip(mStwa, sti, from, replaceKeywordValue(sti.getTargetZipOutputFileName(), time_millis));
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MIRROR)) {
                sync_result = SyncThreadSyncZip.syncMirrorInternalToInternalZip(mStwa, sti, from, replaceKeywordValue(sti.getTargetZipOutputFileName(), time_millis));
            }
        } else if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_INTERNAL) &&
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SDCARD)) {
            from = buildStorageDir(sti.getMasterLocalMountPoint(), sti.getMasterDirectoryName());
            to_temp = buildStorageDir(mGp.safMgr.getSdcardDirectory(), sti.getTargetDirectoryName());

            to = replaceKeywordValue(to_temp, time_millis);

            mStwa.util.addDebugMsg(1, "I", "Sync Internal-To-SDCARD From=" + from + ", To=" + to);

            if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_COPY)) {
                sync_result = SyncThreadSyncFile.syncCopyInternalToExternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MOVE)) {
                sync_result = SyncThreadSyncFile.syncMoveInternalToExternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MIRROR)) {
                sync_result = SyncThreadSyncFile.syncMirrorInternalToExternal(mStwa, sti, from, to);
            }
        } else if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_INTERNAL) &&
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SMB)) {
            //Internal to SMB
            from = buildStorageDir(sti.getMasterLocalMountPoint(), sti.getMasterDirectoryName());
            to_temp = buildSmbHostUrl(sti.getTargetSmbAddr(), sti.getTargetSmbHostName(),
                    sti.getTargetSmbPort(), sti.getTargetSmbShareName(), sti.getTargetDirectoryName());

            to = replaceKeywordValue(to_temp, time_millis);

            mStwa.util.addDebugMsg(1, "I", "Sync Internal-To-SMB From=" + from + ", To=" + to);

            if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_COPY)) {
                sync_result = SyncThreadSyncFile.syncCopyInternalToSmb(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MOVE)) {
                sync_result = SyncThreadSyncFile.syncMoveInternalToSmb(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MIRROR)) {
                sync_result = SyncThreadSyncFile.syncMirrorInternalToSmb(mStwa, sti, from, to);
            }
        } else if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SDCARD) &&
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_INTERNAL)) {
            //External to Internal
            from = buildStorageDir(mGp.safMgr.getSdcardDirectory(), sti.getMasterDirectoryName());
            to_temp = buildStorageDir(sti.getTargetLocalMountPoint(), sti.getTargetDirectoryName());

            to = replaceKeywordValue(to_temp, time_millis);

            mStwa.util.addDebugMsg(1, "I", "Sync SDCARD-To-Internal From=" + from + ", To=" + to);

            if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_COPY)) {
                sync_result = SyncThreadSyncFile.syncCopyExternalToInternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MOVE)) {
                sync_result = SyncThreadSyncFile.syncMoveExternalToInternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MIRROR)) {
                sync_result = SyncThreadSyncFile.syncMirrorExternalToInternal(mStwa, sti, from, to);
            }
        } else if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SDCARD) &&
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SDCARD)) {
            //External to External
            from = buildStorageDir(mGp.safMgr.getSdcardDirectory(), sti.getMasterDirectoryName());
            to_temp = buildStorageDir(mGp.safMgr.getSdcardDirectory(), sti.getTargetDirectoryName());

            to = replaceKeywordValue(to_temp, time_millis);

            mStwa.util.addDebugMsg(1, "I", "Sync SDCARD-To-SDCARD From=" + from + ", To=" + to);

            if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_COPY)) {
                sync_result = SyncThreadSyncFile.syncCopyExternalToExternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MOVE)) {
                sync_result = SyncThreadSyncFile.syncMoveExternalToExternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MIRROR)) {
                sync_result = SyncThreadSyncFile.syncMirrorExternalToExternal(mStwa, sti, from, to);
            }
        } else if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SDCARD) &&
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SMB)) {
            //External to SMB
            from = buildStorageDir(mGp.safMgr.getSdcardDirectory(), sti.getMasterDirectoryName());

            to_temp = buildSmbHostUrl(sti.getTargetSmbAddr(), sti.getTargetSmbHostName(),
                    sti.getTargetSmbPort(), sti.getTargetSmbShareName(), sti.getTargetDirectoryName());

            to = replaceKeywordValue(to_temp, time_millis);

            mStwa.util.addDebugMsg(1, "I", "Sync SDCARD-To-SMB From=" + from + ", To=" + to);

            if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_COPY)) {
                sync_result = SyncThreadSyncFile.syncCopyExternalToSmb(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MOVE)) {
                sync_result = SyncThreadSyncFile.syncMoveExternalToSmb(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MIRROR)) {
                sync_result = SyncThreadSyncFile.syncMirrorExternalToSmb(mStwa, sti, from, to);
            }
        } else if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SMB) &&
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_INTERNAL)) {
            //External to Internal
            from = buildSmbHostUrl(sti.getMasterSmbAddr(), sti.getMasterSmbHostName(),
                    sti.getMasterSmbPort(), sti.getMasterRemoteSmbShareName(), sti.getMasterDirectoryName()) + "/";

            to_temp = buildStorageDir(sti.getTargetLocalMountPoint(), sti.getTargetDirectoryName());

            to = replaceKeywordValue(to_temp, time_millis);

            mStwa.util.addDebugMsg(1, "I", "Sync SMB-To-Internal From=" + from + ", To=" + to);

            if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_COPY)) {
                sync_result = SyncThreadSyncFile.syncCopySmbToInternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MOVE)) {
                sync_result = SyncThreadSyncFile.syncMoveSmbToInternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MIRROR)) {
                sync_result = SyncThreadSyncFile.syncMirrorSmbToInternal(mStwa, sti, from, to);
            }
        } else if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SMB) &&
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SDCARD)) {
            //External to External
            to_temp = buildStorageDir(mGp.safMgr.getSdcardDirectory(), sti.getTargetDirectoryName());

            from = buildSmbHostUrl(sti.getMasterSmbAddr(), sti.getMasterSmbHostName(),
                    sti.getMasterSmbPort(), sti.getMasterRemoteSmbShareName(), sti.getMasterDirectoryName()) + "/";

            to = replaceKeywordValue(to_temp, time_millis);

            mStwa.util.addDebugMsg(1, "I", "Sync SMB-To-SDCARD From=" + from + ", To=" + to);

            if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_COPY)) {
                sync_result = SyncThreadSyncFile.syncCopySmbToExternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MOVE)) {
                sync_result = SyncThreadSyncFile.syncMoveSmbToExternal(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MIRROR)) {
                sync_result = SyncThreadSyncFile.syncMirrorSmbToExternal(mStwa, sti, from, to);
            }
        } else if (sti.getMasterFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SMB) &&
                sti.getTargetFolderType().equals(SyncTaskItem.SYNC_FOLDER_TYPE_SMB)) {
            //External to External
            to_temp = buildSmbHostUrl(sti.getTargetSmbAddr(), sti.getTargetSmbHostName(),
                    sti.getTargetSmbPort(), sti.getTargetSmbShareName(), sti.getTargetDirectoryName()) + "/";

            from = buildSmbHostUrl(sti.getMasterSmbAddr(), sti.getMasterSmbHostName(),
                    sti.getMasterSmbPort(), sti.getMasterRemoteSmbShareName(), sti.getMasterDirectoryName()) + "/";

            to = replaceKeywordValue(to_temp, time_millis);

            mStwa.util.addDebugMsg(1, "I", "Sync SMB-To-SMB From=" + from + ", To=" + to);

            if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_COPY)) {
                sync_result = SyncThreadSyncFile.syncCopySmbToSmb(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MOVE)) {
                sync_result = SyncThreadSyncFile.syncMoveSmbToSmb(mStwa, sti, from, to);
            } else if (sti.getSyncTaskType().equals(SyncTaskItem.SYNC_TASK_TYPE_MIRROR)) {
                sync_result = SyncThreadSyncFile.syncMirrorSmbToSmb(mStwa, sti, from, to);
            }
        }
        return sync_result;
    }

    ;

    private String replaceKeywordValue(String replaceable_string, Long time_millis) {
        String c_date = StringUtil.convDateTimeTo_YearMonthDayHourMin(time_millis);
        String c_date_yyyy = c_date.substring(0, 4);
        String c_date_mm = c_date.substring(5, 7);
        String c_date_dd = c_date.substring(8, 10);
        SimpleDateFormat sdf = new SimpleDateFormat("DDD");
        Date date = new Date();
        date.setTime(time_millis);
        String day_of_year = sdf.format(date);

        String to_temp = null;
        to_temp = replaceable_string.replaceAll(SMBSYNC2_REPLACEABLE_KEYWORD_YEAR, c_date_yyyy)
                .replaceAll(SMBSYNC2_REPLACEABLE_KEYWORD_MONTH, c_date_mm)
                .replaceAll(SMBSYNC2_REPLACEABLE_KEYWORD_DAY, c_date_dd)
                .replaceAll(SMBSYNC2_REPLACEABLE_KEYWORD_DAY_OF_YEAR, day_of_year);
//        Log.v("","org="+replaceable_string+", after="+to_temp);
        return to_temp;
    }

    private String buildStorageDir(String base, String dir) {
        if (dir.equals("")) return base;
        else return base + "/" + dir;
    }

    private String buildSmbHostUrl(String addr, String hostname, String port, String share, String dir) {
        String result = "";
        String smb_host = "smb://";
        if (!addr.equals("")) smb_host = smb_host + addr;
        else smb_host = smb_host + hostname;
        if (!port.equals("")) smb_host = smb_host + ":" + port;
        smb_host = smb_host + "/" + share;
        if (!dir.equals("")) result = smb_host + "/" + dir;
        else result = smb_host;
        return result;
    }

//    private CIFSContext setSmbAuth(BaseContext bc, String domain, String user, String pass) {
//        String tuser = null, tpass = null;
//        if (user.length() != 0) tuser = user;
//        if (pass.length() != 0) tpass = pass;
//
//        NtlmPasswordAuthentication creds = new NtlmPasswordAuthentication(bc, "", tuser, tpass);
//        CIFSContext smb_auth = bc.withCredentials(creds);
//
//        return smb_auth;
//    }

    final public static boolean createDirectoryToInternalStorage(SyncThreadWorkArea stwa, SyncTaskItem sti, String dir) {
        boolean result = false;
        File lf = new File(dir);
        if (!lf.exists()) {
            if (!sti.isSyncTestMode()) {
                result = lf.mkdirs();
                if (result && stwa.gp.settingDebugLevel >= 1)
                    stwa.util.addDebugMsg(1, "I", "createDirectoryToInternalStorage directory created, dir=" + dir);
            } else {
                if (stwa.gp.settingDebugLevel >= 1)
                    stwa.util.addDebugMsg(1, "I", "createDirectoryToInternalStorage directory created, dir=" + dir);
            }
        }
        return result;
    }

    final public static boolean createDirectoryToExternalStorage(SyncThreadWorkArea stwa, SyncTaskItem sti, String dir) {
        boolean result = false;
        if (!sti.isSyncTestMode()) {
            File lf = new File(dir);
            boolean i_exists = lf.exists();
            SafFile new_saf = stwa.gp.safMgr.getSafFileBySdcardPath(stwa.gp.safMgr.getSdcardSafFile(), dir, true);
            result = (new_saf != null) ? true : false;
            if (result && !i_exists && stwa.gp.settingDebugLevel >= 1)
                stwa.util.addDebugMsg(1, "I", "createDirectoryToExternalStorage directory created, dir=" + dir);
            stwa.util.addDebugMsg(2, "I", "createDirectoryToExternalStorage result=" + result + ", exists=" + i_exists + ", new_saf=" + new_saf);
        } else {
            if (stwa.gp.settingDebugLevel >= 1)
                stwa.util.addDebugMsg(1, "I", "createDirectoryToExternalStorage directory created, dir=" + dir);
        }
        return result;
    }

    final public static void createDirectoryToSmb(SyncThreadWorkArea stwa, SyncTaskItem sti, String dir,
                                                  JcifsAuth auth) throws MalformedURLException, JcifsException {
        JcifsFile sf = new JcifsFile(dir, auth);
        if (!sti.isSyncTestMode()) {
            if (!sf.exists()) {
                sf.mkdirs();
                if (stwa.gp.settingDebugLevel >= 1)
                    stwa.util.addDebugMsg(1, "I", "createDirectoryToSmb directory created, dir=" + dir);
            }
        } else {
            if (!sf.exists()) {
                if (stwa.gp.settingDebugLevel >= 1)
                    stwa.util.addDebugMsg(1, "I", "createDirectoryToSmb directory created, dir=" + dir);
            }
        }
    }

    static public void deleteExternalStorageItem(SyncThreadWorkArea stwa, boolean del_dir, SyncTaskItem sti, String tmp_target) {
        if (stwa.gp.settingDebugLevel >= 1)
            stwa.util.addDebugMsg(1, "I", "deleteExternalStorageItem entered, del=" + tmp_target);
        if (!tmp_target.equals(stwa.gp.safMgr.getSdcardDirectory())) {
            File lf_tmp = new File(tmp_target);
            if (lf_tmp.exists()) {
                deleteExternalStorageFile(stwa, sti, tmp_target, lf_tmp);
            }
        }
    }

    static public void deleteExternalStorageFile(SyncThreadWorkArea stwa, SyncTaskItem sti, String fp, File df) {
        if (stwa.gp.settingDebugLevel >= 2)
            stwa.util.addDebugMsg(2, "I", "deleteExternalStorageFile entered, del=" + fp);
//		File df=new File(fp);
        if (df.isDirectory()) {
            File[] fl = df.listFiles();
            if (fl != null && fl.length > 0) {
                for (File c_item : fl) {
                    if (c_item.isDirectory()) {
                        deleteExternalStorageFile(stwa, sti, fp + "/" + c_item.getName(), c_item);
//						stwa.totalDeleteCount++;
//						SafFile sf=SafUtil.getSafDocumentFileByPath(stwa.safCA, c_item.getPath(), true);
//						if (!sti.isSyncTestMode()) sf.delete();
//						showMsg(stwa,false, sti.getSyncTaskName(), "I", fp+"/"+c_item.getName(), c_item.getName(),
//								stwa.gp.appContext.getString(R.string.msgs_mirror_task_dir_deleted));
                    } else {
                        stwa.totalDeleteCount++;
                        SafFile sf = stwa.gp.safMgr.getSafFileBySdcardPath(stwa.gp.safMgr.getSdcardSafFile(), c_item.getPath(), false);
                        if (!sti.isSyncTestMode()) {
                            sf.delete();
                            scanMediaFile(stwa, fp);
                        }
                        showMsg(stwa, false, sti.getSyncTaskName(), "I", fp + "/" + c_item.getName(), c_item.getName(),
                                stwa.gp.appContext.getString(R.string.msgs_mirror_task_file_deleted));
                    }
                    if (!stwa.gp.syncThreadControl.isEnabled()) {
                        break;
                    }
                }
                stwa.totalDeleteCount++;
                SafFile sf = stwa.gp.safMgr.getSafFileBySdcardPath(stwa.gp.safMgr.getSdcardSafFile(), df.getPath(), true);
                if (!sti.isSyncTestMode()) {
                    sf.delete();
                    scanMediaFile(stwa, fp);
                }
                showMsg(stwa, false, sti.getSyncTaskName(), "I", fp, df.getName(),
                        stwa.gp.appContext.getString(R.string.msgs_mirror_task_dir_deleted));
            } else {
                SafFile sf = stwa.gp.safMgr.getSafFileBySdcardPath(stwa.gp.safMgr.getSdcardSafFile(), df.getPath(), true);
                if (!sti.isSyncTestMode()) {
                    sf.delete();
                    scanMediaFile(stwa, fp);
                }
                stwa.totalDeleteCount++;
                showMsg(stwa, false, sti.getSyncTaskName(), "I", fp, df.getName(),
                        stwa.gp.appContext.getString(R.string.msgs_mirror_task_dir_deleted));
            }
        } else {
            SafFile sf = stwa.gp.safMgr.getSafFileBySdcardPath(stwa.gp.safMgr.getSdcardSafFile(), df.getPath(), false);
            if (!sti.isSyncTestMode()) sf.delete();
            stwa.totalDeleteCount++;
            showMsg(stwa, false, sti.getSyncTaskName(), "I", fp, df.getName(),
                    stwa.gp.appContext.getString(R.string.msgs_mirror_task_file_deleted));

        }
    }

    static public void deleteSmbItem(SyncThreadWorkArea stwa, boolean del_dir, SyncTaskItem sti,
                                     String to_base, String tmp_target, JcifsAuth auth) throws IOException, JcifsException {
        if (stwa.gp.settingDebugLevel >= 1)
            stwa.util.addDebugMsg(1, "I", "deleteSmbItem entered, del=" + tmp_target);
        if (!tmp_target.equals(to_base)) {
            JcifsFile lf_tmp = new JcifsFile(tmp_target, auth);
            if (lf_tmp.exists()) {
                deleteSmbFile(stwa, sti, tmp_target, lf_tmp);
            }
        }
    }

    static public void deleteSmbFile(SyncThreadWorkArea stwa, SyncTaskItem sti, String fp, JcifsFile hf) throws  JcifsException {
        if (stwa.gp.settingDebugLevel >= 2)
            stwa.util.addDebugMsg(2, "I", "deleteSmbFile entered, del=" + fp);
//		JcifsFile hf=new JcifsFile(fp, stwa.ntlmPasswordAuth);
        if (hf.isDirectory()) {
            JcifsFile[] fl = hf.listFiles();
            if (fl != null && fl.length > 0) {
                for (JcifsFile c_item : fl) {
                    if (c_item.isDirectory()) {
                        deleteSmbFile(stwa, sti, fp + c_item.getName(), c_item);
                    } else {
                        stwa.totalDeleteCount++;
                        if (!sti.isSyncTestMode()) c_item.delete();
                        showMsg(stwa, false, sti.getSyncTaskName(), "I", fp + c_item.getName(), c_item.getName(),
                                stwa.gp.appContext.getString(R.string.msgs_mirror_task_dir_deleted));
                    }
                    if (!stwa.gp.syncThreadControl.isEnabled()) {
                        break;
                    }
                }
                stwa.totalDeleteCount++;
                if (!sti.isSyncTestMode()) hf.delete();
                showMsg(stwa, false, sti.getSyncTaskName(), "I", fp, hf.getName(),
                        stwa.gp.appContext.getString(R.string.msgs_mirror_task_dir_deleted));
            } else {
                if (!sti.isSyncTestMode()) hf.delete();
                stwa.totalDeleteCount++;
                showMsg(stwa, false, sti.getSyncTaskName(), "I", fp, hf.getName(),
                        stwa.gp.appContext.getString(R.string.msgs_mirror_task_dir_deleted));
            }
        } else {
            if (!sti.isSyncTestMode()) hf.delete();
            stwa.totalDeleteCount++;
            showMsg(stwa, false, sti.getSyncTaskName(), "I", fp, hf.getName(),
                    stwa.gp.appContext.getString(R.string.msgs_mirror_task_file_deleted));

        }
    }

    static public void deleteInternalStorageItem(SyncThreadWorkArea stwa, boolean del_dir, SyncTaskItem sti, String tmp_target) {
        if (stwa.gp.settingDebugLevel >= 1)
            stwa.util.addDebugMsg(1, "I", "deleteInternalStorageItem entered, del=" + tmp_target);

        if (!tmp_target.equals(stwa.gp.internalRootDirectory)) {
            File lf_tmp = new File(tmp_target);
            if (lf_tmp.exists()) {
                deleteInternalStorageFile(stwa, sti, tmp_target, lf_tmp);
            }
        }
    }

    static public void deleteInternalStorageFile(SyncThreadWorkArea stwa, SyncTaskItem sti, String fp, File lf) {
        if (stwa.gp.settingDebugLevel >= 2)
            stwa.util.addDebugMsg(2, "I", "deleteInternalStorageFile entered, del=" + fp);
//		File lf=new File(fp);
//		Log.v("","name="+fp+", dir="+lf.isDirectory()+", file="+lf.isFile());
        if (lf.isDirectory()) {
            File[] fl = lf.listFiles();
            if (fl != null && fl.length > 0) {
                for (File c_item : fl) {
                    if (lf.isDirectory()) {
                        deleteInternalStorageFile(stwa, sti, fp + "/" + c_item.getName(), c_item);
                    } else {
                        stwa.totalDeleteCount++;
                        if (!sti.isSyncTestMode()) {
                            c_item.delete();
                            scanMediaFile(stwa, fp);
                        }
                        showMsg(stwa, false, sti.getSyncTaskName(), "I", fp + "/" + c_item.getName(), c_item.getName(),
                                stwa.gp.appContext.getString(R.string.msgs_mirror_task_file_deleted));
                    }
                    if (!stwa.gp.syncThreadControl.isEnabled()) {
                        break;
                    }
                }
                stwa.totalDeleteCount++;
                if (!sti.isSyncTestMode()) {
                    lf.delete();
                    scanMediaFile(stwa, fp);
                }
                showMsg(stwa, false, sti.getSyncTaskName(), "I", fp, lf.getName(),
                        stwa.gp.appContext.getString(R.string.msgs_mirror_task_dir_deleted));
            } else {
                if (!sti.isSyncTestMode()) {
                    lf.delete();
                    scanMediaFile(stwa, fp);
                }
                stwa.totalDeleteCount++;
                showMsg(stwa, false, sti.getSyncTaskName(), "I", fp, lf.getName(),
                        stwa.gp.appContext.getString(R.string.msgs_mirror_task_dir_deleted));
            }
        } else {
            if (!sti.isSyncTestMode()) {
                lf.delete();
                scanMediaFile(stwa, fp);
            }
            stwa.totalDeleteCount++;
            showMsg(stwa, false, sti.getSyncTaskName(), "I", fp, lf.getName(),
                    stwa.gp.appContext.getString(R.string.msgs_mirror_task_file_deleted));

        }
    }

    private void reconnectWifi() {
        boolean wifi_reconnect_required = false;
        try {
            ContentResolver contentResolver = mGp.appContext.getContentResolver();
            int policy = Settings.System.getInt(contentResolver, Settings.Global.WIFI_SLEEP_POLICY);
            switch (policy) {
                case Settings.Global.WIFI_SLEEP_POLICY_DEFAULT:
                    // スリープ中のWiFi接続を維持しない
                    wifi_reconnect_required = true;
                    break;
                case Settings.Global.WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED:
                    // スリープ中のWiFi接続を電源接続時にのみ維持する
                    wifi_reconnect_required = true;
                    break;
                case Settings.Global.WIFI_SLEEP_POLICY_NEVER:
                    // スリープ中のWiFi接続を常に維持する
                    break;
            }
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }
        if (isWifiOn()) {
            getWifiConnectedAP();
            WifiManager wm = (WifiManager) mGp.appContext.getSystemService(Context.WIFI_SERVICE);
            SupplicantState ss = wm.getConnectionInfo().getSupplicantState();
            mStwa.util.addDebugMsg(1, "I", "reconnectWifi ss=" + ss.toString());
            if (!GlobalParameters.isScreenOn(mGp.appContext, mStwa.util) && wifi_reconnect_required
                    ) {// && !ss.equals(SupplicantState.COMPLETED)) {
                @SuppressWarnings("deprecation")
                WakeLock wl = ((PowerManager) mGp.appContext.getSystemService(Context.POWER_SERVICE))
                        .newWakeLock(PowerManager.FULL_WAKE_LOCK
                                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                , "SMBSync2-thread-reconnect");
                long wt = 10 * 1000;
                wl.acquire(5000);
                mStwa.util.addDebugMsg(1, "I", "reconnectWifi reconnect issued");
                long to = 0;
                while (!wm.getConnectionInfo().getSupplicantState().equals("")) {
                    to += 100;
                    if (wt < to) break;
                    SystemClock.sleep(100);
                    mStwa.util.addDebugMsg(1, "I", "reconnectWifi ssw=" + wm.getConnectionInfo().getSupplicantState().toString());
                }
            }

            ss = wm.getConnectionInfo().getSupplicantState();
            mStwa.util.addDebugMsg(1, "I", "reconnectWifi ss=" + ss.toString());
            getWifiConnectedAP();
        }

    }

    private String isWifiConditionSatisfied(SyncTaskItem sti) {
        String result = "";
        if (sti.getSyncWifiStatusOption().equals(SyncTaskItem.SYNC_WIFI_STATUS_WIFI_OFF)) {
            //NOP
        } else {
            if (sti.getSyncWifiStatusOption().equals(SyncTaskItem.SYNC_WIFI_STATUS_WIFI_CONNECT_ANY_AP)) {
                if (getWifiConnectedAP().equals(""))
                    result = mGp.appContext.getString(R.string.msgs_mirror_sync_can_not_start_wifi_ap_not_connected);
            } else if (sti.getSyncWifiStatusOption().equals(SyncTaskItem.SYNC_WIFI_STATUS_WIFI_CONNECT_SPECIFIC_AP)) {
                ArrayList<String> wl = sti.getSyncWifiConnectionWhiteList();
                ArrayList<Pattern> inc = new ArrayList<Pattern>();
                int flags = Pattern.CASE_INSENSITIVE;
                for (String apl : wl) {
                    if (apl.startsWith("I")) {
                        String prefix = "", suffix = "";
                        if (apl.substring(1).endsWith("*")) suffix = "$";
                        inc.add(Pattern.compile(prefix + MiscUtil.convertRegExp(apl.substring(1)) + suffix, flags));
                        mStwa.util.addDebugMsg(1, "I", "isWifiConditionSatisfied include added=" + inc.get(inc.size() - 1).toString());
                    }
                }
                if (!getWifiConnectedAP().equals("")) {
                    if (inc.size() > 0) {
                        Matcher mt;
                        boolean found = false;
                        for (Pattern pat : inc) {
                            mt = pat.matcher(mGp.wifiSsid);
                            if (mt.find()) {
                                found = true;
                                mStwa.util.addDebugMsg(1, "I", "isWifiConditionSatisfied include matched=" + pat.toString());
                                break;
                            }
                        }
                        if (!found) {
                            if (sti.isSyncTaskSkipIfConnectAnotherWifiSsid()) {
                                result = mGp.appContext.getString(R.string.msgs_mirror_sync_skipped_wifi_ap_conn_other);
                            } else {
                                result = mGp.appContext.getString(R.string.msgs_mirror_sync_can_not_start_wifi_ap_conn_other);
                            }
                        }
                    }
                } else {
                    result = mGp.appContext.getString(R.string.msgs_mirror_sync_can_not_start_wifi_ap_not_connected);
                }
            }
        }
        mStwa.util.addDebugMsg(1, "I", "isWifiConditionSatisfied exited, " + "option=" + sti.getSyncWifiStatusOption() + ", result=" + result);
        return result;
    }

    private String getWifiConnectedAP() {
        String result = "";
        WifiManager wm = (WifiManager) mGp.appContext.getSystemService(Context.WIFI_SERVICE);
        result = SyncUtil.getWifiSsidName(wm);
        mStwa.util.addDebugMsg(1, "I", "getWifiConnectedAP SSID=" + result);
        return result;
    }

    private boolean isWifiOn() {
        boolean result = false;
        WifiManager wm = (WifiManager) mGp.appContext.getSystemService(Context.WIFI_SERVICE);
        result = wm.isWifiEnabled();
        return result;
    }

    private boolean setWifiOn() {
        boolean result = false;
        WifiManager wm = (WifiManager) mGp.appContext.getSystemService(Context.WIFI_SERVICE);
        result = wm.setWifiEnabled(true);
        return result;
    }

    private boolean setWifiOff() {
        boolean result = false;
        WifiManager wm = (WifiManager) mGp.appContext.getSystemService(Context.WIFI_SERVICE);
        result = wm.setWifiEnabled(false);
        return result;
    }

    static public void showProgressMsg(final SyncThreadWorkArea stwa, final String task_name, final String msg) {
        NotificationUtil.showOngoingMsg(stwa.gp, 0, task_name, msg);
        stwa.gp.progressSpinSyncprofText = task_name;
        stwa.gp.progressSpinMsgText = msg;
        if (stwa.gp.dialogWindowShowed && stwa.gp.progressSpinSyncprof != null) {
            stwa.gp.uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (stwa.gp.progressSpinSyncprof != null && !stwa.gp.activityIsBackground) {
                        stwa.gp.progressSpinSyncprof.setText(stwa.gp.progressSpinSyncprofText);
                        ;
                        stwa.gp.progressSpinMsg.setText(stwa.gp.progressSpinMsgText);
                    }
                }
            });
        }
    }

    static public void showMsg(final SyncThreadWorkArea stwa, boolean log_only,
                               final String task_name, final String cat,
                               final String full_path, final String file_name, final String msg) {
        stwa.gp.progressSpinSyncprofText = task_name;
        stwa.gp.progressSpinMsgText = file_name.concat(" ").concat(msg);
        if (!log_only) {
            NotificationUtil.showOngoingMsg(stwa.gp, System.currentTimeMillis(), task_name, file_name, msg);
            if (stwa.gp.dialogWindowShowed && stwa.gp.progressSpinSyncprof != null) {
                stwa.gp.uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (stwa.gp.progressSpinSyncprof != null && !stwa.gp.activityIsBackground) {
                            stwa.gp.progressSpinSyncprof.setText(stwa.gp.progressSpinSyncprofText);
                            stwa.gp.progressSpinMsg.setText(stwa.gp.progressSpinMsgText);
                        }
                    }
                });
            }
        }
        String lm = full_path.equals("") ? msg : full_path.concat(" ").concat(msg);
        if (task_name.equals("")) {
            stwa.util.addLogMsg(cat, lm);
            if (stwa.syncHistoryWriter != null) {
                String print_msg = "";
                print_msg = stwa.util.buildPrintMsg(cat, lm);
                stwa.syncHistoryWriter.println(print_msg);
            }
        } else {
            stwa.util.addLogMsg(cat, task_name, " ", lm);
            if (stwa.syncHistoryWriter != null) {
                String print_msg = "";
                print_msg = stwa.util.buildPrintMsg(cat, task_name, " ", lm);
                stwa.syncHistoryWriter.println(print_msg);
            }
        }
    }

    public static void printStackTraceElement(SyncThreadWorkArea stwa, StackTraceElement[] ste) {
        String print_msg = "";
        for (int i = 0; i < ste.length; i++) {
            stwa.util.addLogMsg("E", "", ste[i].toString());
            if (stwa.syncHistoryWriter != null) {
                print_msg = stwa.util.buildPrintMsg("E", ste[i].toString());
                stwa.syncHistoryWriter.println(print_msg);
            }
        }
    }

    static final public boolean sendConfirmRequest(SyncThreadWorkArea stwa, SyncTaskItem sti, String type, String url) {
        boolean result = true;
        int rc = 0;
        if (stwa.gp.settingDebugLevel >= 2)
            stwa.util.addDebugMsg(2, "I", "sendConfirmRequest entered type=" + type +
                    ", Override=" + sti.isSyncOverrideCopyMoveFile() + ", Confirm=" + sti.isSyncConfirmOverrideOrDelete() +
                    ", fp=", url);
        if (sti.isSyncConfirmOverrideOrDelete()) {
            boolean ignore_confirm = true;
            if (type.equals(SMBSYNC2_CONFIRM_REQUEST_DELETE_DIR) || type.equals(SMBSYNC2_CONFIRM_REQUEST_DELETE_FILE)) {
                if (stwa.confirmDeleteResult == SMBSYNC2_CONFIRM_RESP_YESALL) result = true;
                else if (stwa.confirmDeleteResult == SMBSYNC2_CONFIRM_RESP_NOALL) result = false;
                else ignore_confirm = false;
            } else if (type.equals(SMBSYNC2_CONFIRM_REQUEST_COPY)) {
                if (stwa.confirmCopyResult == SMBSYNC2_CONFIRM_RESP_YESALL) result = true;
                else if (stwa.confirmCopyResult == SMBSYNC2_CONFIRM_RESP_NOALL) result = false;
                else ignore_confirm = false;
            } else if (type.equals(SMBSYNC2_CONFIRM_REQUEST_MOVE)) {
                if (stwa.confirmMoveResult == SMBSYNC2_CONFIRM_RESP_YESALL) result = true;
                else if (stwa.confirmMoveResult == SMBSYNC2_CONFIRM_RESP_NOALL) result = false;
                else ignore_confirm = false;
            }
            if (!ignore_confirm) {
                try {
                    String msg = "";
                    if (type.equals(SMBSYNC2_CONFIRM_REQUEST_DELETE_DIR)) {
                        msg = stwa.gp.appContext.getString(R.string.msgs_mirror_confirm_please_check_confirm_msg_delete_dir);
                    } else if (type.equals(SMBSYNC2_CONFIRM_REQUEST_DELETE_FILE)) {
                        msg = stwa.gp.appContext.getString(R.string.msgs_mirror_confirm_please_check_confirm_msg_delete_file);
                    } else if (type.equals(SMBSYNC2_CONFIRM_REQUEST_COPY)) {
                        msg = stwa.gp.appContext.getString(R.string.msgs_mirror_confirm_please_check_confirm_msg_copy);
                    } else if (type.equals(SMBSYNC2_CONFIRM_REQUEST_MOVE)) {
                        msg = stwa.gp.appContext.getString(R.string.msgs_mirror_confirm_please_check_confirm_msg_move);
                    }
                    NotificationUtil.showOngoingMsg(stwa.gp, 0, msg);
                    stwa.gp.confirmDialogShowed = true;
                    stwa.gp.confirmDialogFilePath = url;
                    stwa.gp.confirmDialogMethod = type;
                    stwa.gp.syncThreadConfirm.initThreadCtrl();
                    stwa.gp.releaseWakeLock(stwa.util);
                    if (stwa.gp.callbackStub != null) {
                        stwa.gp.callbackStub.cbShowConfirmDialog(url, type);
                    }
                    synchronized (stwa.gp.syncThreadConfirm) {
                        stwa.gp.syncThreadConfirmWait = true;
                        stwa.gp.syncThreadConfirm.wait();//Posted by SMBSyncService#aidlConfirmResponse()
                        stwa.gp.syncThreadConfirmWait = false;
                    }
                    stwa.gp.acquireWakeLock(stwa.util);
                    if (type.equals(SMBSYNC2_CONFIRM_REQUEST_DELETE_DIR) || type.equals(SMBSYNC2_CONFIRM_REQUEST_DELETE_FILE)) {
                        rc = stwa.confirmDeleteResult = stwa.gp.syncThreadConfirm.getExtraDataInt();
                        if (stwa.confirmDeleteResult > 0) result = true;
                        else result = false;
                        if (stwa.confirmDeleteResult == SMBSYNC2_CONFIRM_RESP_CANCEL)
                            stwa.gp.syncThreadControl.setDisabled();
                    } else if (type.equals(SMBSYNC2_CONFIRM_REQUEST_COPY)) {
                        rc = stwa.confirmCopyResult = stwa.gp.syncThreadConfirm.getExtraDataInt();
                        if (stwa.confirmCopyResult > 0) result = true;
                        else result = false;
                        if (stwa.confirmCopyResult == SMBSYNC2_CONFIRM_RESP_CANCEL)
                            stwa.gp.syncThreadControl.setDisabled();
                    } else if (type.equals(SMBSYNC2_CONFIRM_REQUEST_MOVE)) {
                        rc = stwa.confirmMoveResult = stwa.gp.syncThreadConfirm.getExtraDataInt();
                        if (stwa.confirmMoveResult > 0) result = true;
                        else result = false;
                        if (stwa.confirmMoveResult == SMBSYNC2_CONFIRM_RESP_CANCEL)
                            stwa.gp.syncThreadControl.setDisabled();
                    }
                } catch (RemoteException e) {
                    stwa.util.addLogMsg("E", "", "RemoteException occured");
                    printStackTraceElement(stwa, e.getStackTrace());
                } catch (InterruptedException e) {
                    stwa.util.addLogMsg("E", "", "InterruptedException occured");
                    printStackTraceElement(stwa, e.getStackTrace());
                }
            }
        }
        if (stwa.gp.settingDebugLevel >= 2)
            stwa.util.addDebugMsg(2, "I", "sendConfirmRequest result=" + result, ", rc=" + rc);

        return result;
    }

    static final public boolean isLocalFileLastModifiedWasDifferent(SyncThreadWorkArea stwa,
                                                                    SyncTaskItem sti,
                                                                    ArrayList<FileLastModifiedTimeEntry> curr_last_modified_list,
                                                                    ArrayList<FileLastModifiedTimeEntry> new_last_modified_list,
                                                                    String fp, long l_lm, long r_lm) {
        boolean result = FileLastModifiedTime.isCurrentListWasDifferent(
                curr_last_modified_list, new_last_modified_list,
                fp, l_lm, r_lm, stwa.syncDifferentFileAllowableTime);
        if (stwa.gp.settingDebugLevel >= 3)
            stwa.util.addDebugMsg(3, "I", "isLocalFileLastModifiedWasDifferent result=" + result + ", item=" + fp);
        return result;
    }

    static final public void deleteLocalFileLastModifiedEntry(SyncThreadWorkArea stwa,
                                                              ArrayList<FileLastModifiedTimeEntry> curr_last_modified_list,
                                                              ArrayList<FileLastModifiedTimeEntry> new_last_modified_list,
                                                              String fp) {
        FileLastModifiedTime.deleteLastModifiedItem(
                curr_last_modified_list, new_last_modified_list, fp);
        if (stwa.gp.settingDebugLevel >= 3)
            stwa.util.addDebugMsg(3, "I", "deleteLocalFileLastModifiedEntry entry=" + fp);

    }

    static final public boolean updateLocalFileLastModifiedList(SyncThreadWorkArea stwa,
                                                                ArrayList<FileLastModifiedTimeEntry> curr_last_modified_list,
                                                                ArrayList<FileLastModifiedTimeEntry> new_last_modified_list,
                                                                String to_dir, long l_lm, long r_lm) {
        if (stwa.setLastModifiedIsFunctional) return false;
        stwa.localFileLastModListModified = true;
        return FileLastModifiedTime.updateLastModifiedList(
                curr_last_modified_list, new_last_modified_list, to_dir, l_lm, r_lm);
    }

    static final public void addLastModifiedItem(SyncThreadWorkArea stwa,
                                                 ArrayList<FileLastModifiedTimeEntry> curr_last_modified_list,
                                                 ArrayList<FileLastModifiedTimeEntry> new_last_modified_list,
                                                 String to_dir, long l_lm, long r_lm) {
        FileLastModifiedTime.addLastModifiedItem(
                curr_last_modified_list, new_last_modified_list, to_dir, l_lm, r_lm);
        if (stwa.gp.settingDebugLevel >= 3)
            stwa.util.addDebugMsg(3, "I", "addLastModifiedItem entry=" + to_dir);
    }

    final private boolean isSetLastModifiedFunctional(String lmp) {
        boolean result =
                FileLastModifiedTime.isSetLastModifiedFunctional(lmp);
        if (mStwa.gp.settingDebugLevel >= 1)
            mStwa.util.addDebugMsg(1, "I", "isSetLastModifiedFunctional result=" + result + ", Directory=" + lmp);
        return result;
    }

    static final public boolean isFileChanged(SyncThreadWorkArea stwa, SyncTaskItem sti, String fp, File lf, JcifsFile hf, boolean ac)
            throws JcifsException {
        long hf_time = 0, hf_length = 0;
        boolean hf_exists = hf.exists();

        if (hf_exists) {
            hf_time = hf.getLastModified();
            hf_length = hf.length();
        }
        return isFileChangedDetailCompare(stwa, sti, fp, lf, hf_exists, hf_time, hf_length, ac);
    }

    static final public boolean isFileChanged(SyncThreadWorkArea stwa, SyncTaskItem sti, String fp, File mf, File tf, boolean ac)
            throws JcifsException {
        long tf_time = 0, tf_length = 0;
        boolean tf_exists = tf.exists();

        if (tf_exists) {
            tf_time = tf.lastModified();
            tf_length = tf.length();
        }
        return isFileChangedDetailCompare(stwa, sti, fp, mf, tf_exists, tf_time, tf_length, ac);
    }

    static final public boolean isFileChanged(SyncThreadWorkArea stwa, SyncTaskItem sti, String fp,
                                              JcifsFile mf, JcifsFile tf, boolean ac)
            throws JcifsException {

        long lf_time = 0, lf_length = 0;
        boolean lf_exists = mf.exists();

        if (lf_exists) {
            lf_time = mf.getLastModified();
            lf_length = mf.length();
        }

        return isFileChangedDetailCompare(stwa, sti, fp,
                lf_exists, lf_time, lf_length, mf.getPath(),
                tf.exists(), tf.getLastModified(), tf.length(), ac);

    }

    ;

    static final public boolean isFileChangedDetailCompare(SyncThreadWorkArea stwa, SyncTaskItem sti, String fp,
                                                           File lf, boolean hf_exists, long hf_time, long hf_length, boolean ac) throws JcifsException {
        long lf_time = 0, lf_length = 0;
        boolean lf_exists = lf.exists();

        if (lf_exists) {
            lf_time = lf.lastModified();
            lf_length = lf.length();
        }

        return isFileChangedDetailCompare(stwa, sti, fp,
                lf_exists, lf_time, lf_length, lf.getPath(),
                hf_exists, hf_time, hf_length, ac);
    }

    ;

    static final public boolean isFileChangedDetailCompare(SyncThreadWorkArea stwa, SyncTaskItem sti, String fp,
                                                           boolean lf_exists, long lf_time, long lf_length, String lf_path,
                                                           boolean hf_exists, long hf_time, long hf_length, boolean ac) {
        boolean diff = false;
        boolean exists_diff = false;

        long time_diff = Math.abs((hf_time - lf_time));
        long length_diff = Math.abs((hf_length - lf_length));

        if (hf_exists != lf_exists) exists_diff = true;
        if (exists_diff || (sti.isSyncDifferentFileBySize() && length_diff > 0) || ac) {
            if (!stwa.setLastModifiedIsFunctional) {//Use lastModified
                if (lf_exists) {
                    updateLocalFileLastModifiedList(stwa, stwa.currLastModifiedList, stwa.newLastModifiedList,
                            lf_path, lf_time, hf_time);
                } else {
                    boolean updated =
                            updateLocalFileLastModifiedList(stwa, stwa.currLastModifiedList, stwa.newLastModifiedList,
                                    lf_path, lf_time, hf_time);
                    if (!updated)
                        addLastModifiedItem(stwa, stwa.currLastModifiedList, stwa.newLastModifiedList,
                                lf_path, lf_time, hf_time);
                }
            }
            diff = true;
        } else {//Check lastModified()
            if (sti.isSyncDifferentFileByTime()) {
                if (stwa.setLastModifiedIsFunctional) {//Use lastModified
                    if (time_diff > stwa.syncDifferentFileAllowableTime) { //LastModified was changed
                        diff = true;
                    } else diff = false;
                } else {//Use Filelist
                    String lfp = lf_path;
                    diff = isLocalFileLastModifiedWasDifferent(stwa, sti,
                            stwa.currLastModifiedList,
                            stwa.newLastModifiedList,
                            lfp, lf_time, hf_time);
//					Log.v("","lfp="+lfp+", lf_time="+lf_time+", hf_time="+hf_time);
                }
            }
        }
        if (stwa.gp.settingDebugLevel >= 3) {
            stwa.util.addDebugMsg(3, "I", "isFileChangedDetailCompare");
            if (hf_exists) stwa.util.addDebugMsg(3, "I", "Master file length=" + hf_length +
                    ", last modified(ms)=" + hf_time +
                    ", date=" + StringUtil.convDateTimeTo_YearMonthDayHourMinSec((hf_time / 1000) * 1000));
            else stwa.util.addDebugMsg(3, "I", "Master file was not exists");
            if (lf_exists) stwa.util.addDebugMsg(3, "I", "Target file length=" + lf_length +
                    ", last modified(ms)=" + lf_time +
                    ", date=" + StringUtil.convDateTimeTo_YearMonthDayHourMinSec((lf_time / 1000) * 1000));
            else stwa.util.addDebugMsg(3, "I", "Target file was not exists");
            stwa.util.addDebugMsg(3, "I", "allcopy=" + ac + ",exists_diff=" + exists_diff +
                    ",time_diff=" + time_diff + ",length_diff=" + length_diff + ", diff=" + diff);
        }
        return diff;
    }

    ;

    static final public boolean isFileChangedForLocalToRemote(SyncThreadWorkArea stwa, SyncTaskItem sti,
                                                              String fp, File lf, JcifsFile hf, boolean ac)
            throws JcifsException {
        boolean diff = false;
        long hf_time = 0, hf_length = 0;
        boolean hf_exists = hf.exists();

        if (hf_exists) {
            hf_time = hf.getLastModified();
            hf_length = hf.length();
        }
        long lf_time = 0, lf_length = 0;
        boolean lf_exists = lf.exists();
        boolean exists_diff = false;

        if (lf_exists) {
            lf_time = lf.lastModified();
            lf_length = lf.length();
        }
        long time_diff = Math.abs((hf_time - lf_time));
        long length_diff = Math.abs((hf_length - lf_length));
//		long time_diff_tz1=Math.abs(hf_time-lf_time);
//		long diff_tz_2=Math.abs(hf_time-(lf_time-(timeZone*2)));

        if (hf_exists != lf_exists) exists_diff = true;
        if (exists_diff || (sti.isSyncDifferentFileBySize() && length_diff > 0) || ac) {
            diff = true;
        } else {//Check lastModified()
            if (!sti.isSyncDoNotResetLastModifiedSmbFile() && sti.isSyncDifferentFileByTime()) {
                if (time_diff > stwa.syncDifferentFileAllowableTime) { //LastModified was changed
                    diff = true;
                } else diff = false;
            }
        }
        if (stwa.gp.settingDebugLevel >= 3) {
            stwa.util.addDebugMsg(3, "I", "isFileChangedForLocalToRemote");
            if (hf_exists) stwa.util.addDebugMsg(3, "I", "Remote file length=" + hf_length +
                    ", last modified(ms)=" + hf_time +
                    ", date=" + StringUtil.convDateTimeTo_YearMonthDayHourMinSec((hf_time / 1000) * 1000));
            else stwa.util.addDebugMsg(3, "I", "Remote file was not exists");
            if (lf_exists) stwa.util.addDebugMsg(3, "I", "Local  file length=" + lf_length +
                    ", last modified(ms)=" + lf_time +
                    ", date=" + StringUtil.convDateTimeTo_YearMonthDayHourMinSec((lf_time / 1000) * 1000));
            else stwa.util.addDebugMsg(3, "I", "Local  file was not exists");
            stwa.util.addDebugMsg(3, "I", "allcopy=" + ac + ",exists_diff=" + exists_diff +
                    ",time_diff=" + time_diff +//", time_zone_diff="+time_diff_tz1+
                    ",length_diff=" + length_diff + ", diff=" + diff);
        }
        return diff;
    }

    ;


    static public boolean isHiddenDirectory(SyncThreadWorkArea stwa, SyncTaskItem sti, File lf) {
        boolean result = false;
        if (sti.isSyncHiddenDirectory()) result = false;
        else {
            if (lf.getName().substring(0, 1).equals(".")) result = true;
        }
        if (stwa.gp.settingDebugLevel >= 2)
            stwa.util.addDebugMsg(2, "I", "isHiddenDirectory(Local) result=" + result + ", Name=" + lf.getName());
        return result;
    }

    ;

    static public boolean isHiddenDirectory(SyncThreadWorkArea stwa, SyncTaskItem sti, JcifsFile hf) throws JcifsException {
        boolean result = false;
        if (sti.isSyncHiddenDirectory()) result = false;
        else {
            if (hf.isHidden()) result = true;
        }
        if (stwa.gp.settingDebugLevel >= 2) {
            String name = hf.getName().replace("/", "");
            stwa.util.addDebugMsg(2, "I", "isHiddenDirectory(Remote) result=" + result + ", Name=" + name);
        }
        return result;
    }

    ;

    static public boolean isHiddenFile(SyncThreadWorkArea stwa, SyncTaskItem sti, File lf) {
        boolean result = false;
        if (sti.isSyncHiddenFile()) result = false;
        else {
            if (lf.getName().substring(0, 1).equals(".")) result = true;
        }
        if (stwa.gp.settingDebugLevel >= 2) {
            stwa.util.addDebugMsg(2, "I", "isHiddenFile(Local) result=" + result + ", Name=" + lf.getName());
        }
        return result;
    }

    ;

    static public boolean isHiddenFile(SyncThreadWorkArea stwa, SyncTaskItem sti, JcifsFile hf) throws JcifsException {
        boolean result = false;
        if (sti.isSyncHiddenFile()) result = false;
        else {
            if (hf.isHidden()) result = true;
        }
//		if (!sti.isSyncHiddenFile() && hf.isHidden()) result=true;
        if (stwa.gp.settingDebugLevel >= 2)
            stwa.util.addDebugMsg(2, "I", "isHiddenFile(Remote) result=" + result + ", Name=" + hf.getName().replace("/", ""));
        return result;
    }

    ;


    static final public boolean isFileSelected(SyncThreadWorkArea stwa, SyncTaskItem sti, String url) {
        boolean filtered = false;
        Matcher mt;

        if (!sti.isSyncProcessRootDirFile()) {//「root直下のファイルは処理するオプションが無効
//			Log.v("","url="+url);
            String tmp_d = "", tmp_url = url;
            if (url.startsWith("/")) tmp_url = url.substring(1);

            if (sti.getMasterDirectoryName().equals("")) {
                if (tmp_url.substring(tmp_url.length()).equals("/"))
                    tmp_d = tmp_url.substring(0, tmp_url.length() - 1);
                else tmp_d = tmp_url;
//				if (tmp_url.endsWith("/")) tmp_d=tmp_url.substring(0, tmp_url.length()-1);
            } else {
                if (tmp_url.substring(tmp_url.length()).equals("/"))
                    tmp_d = tmp_url.replace(sti.getMasterDirectoryName() + "/", "");
                else tmp_d = tmp_url.replace(sti.getMasterDirectoryName(), "");
//				if (tmp_url.endsWith("/")) tmp_d=tmp_url.replace(sti.getMasterDirectoryName()+"/","");
            }

//			Log.v("","tmp_d="+tmp_d+", tmp_url="+tmp_url+", url="+url);
            if (tmp_d.indexOf("/") < 0) {
                //root直下なので処理しない
                if (stwa.gp.settingDebugLevel >= 2)
                    stwa.util.addDebugMsg(2, "I", "isFileSelected not filtered, " +
                            "because Master Dir not processed was effective");
//				String npe=null;
//				npe.length();
                return false;
            }
        }
        ;

        String temp_fid = url.substring(url.lastIndexOf("/") + 1, url.length());
//		Log.v("","t="+temp_fid+", url="+url+", pattern="+fileFilterInclude);
        if (stwa.fileFilterInclude == null) {
            // nothing filter
            filtered = true;
        } else {
            mt = stwa.fileFilterInclude.matcher(temp_fid);
            if (mt.find()) filtered = true;
            if (stwa.gp.settingDebugLevel >= 2)
                stwa.util.addDebugMsg(2, "I", "isFileSelected Include result:" + filtered);
        }
        if (stwa.fileFilterExclude == null) {
            //nop
        } else {
            mt = stwa.fileFilterExclude.matcher(temp_fid);
            if (mt.find()) filtered = false;
            if (stwa.gp.settingDebugLevel >= 2)
                stwa.util.addDebugMsg(2, "I", "isFileSelected Exclude result:" + filtered);
        }
        if (stwa.gp.settingDebugLevel >= 2)
            stwa.util.addDebugMsg(2, "I", "isFileSelected result:" + filtered);
        return filtered;
    }

    ;

    static final public boolean isDirectorySelectedByFileName(SyncThreadWorkArea stwa, String f_dir_name) {

        String n_fp = "";
        String t_dir = f_dir_name;
        String n_dir = "";
        if (f_dir_name.startsWith("/")) t_dir = f_dir_name.substring(1);
        if (t_dir.endsWith("/")) n_fp = t_dir.substring(0, t_dir.length());
        else n_fp = t_dir;

        if (n_fp.lastIndexOf("/") > 0) n_dir = n_fp.substring(0, n_fp.lastIndexOf("/"));
//		Log.v("","by file f_dir="+f_dir_name+", n_dir="+n_dir+", t_dir="+t_dir);
        boolean result = isDirectorySelectedByDirectoryName(stwa, n_dir);
//		if (!result) {
//			Thread.dumpStack();
//		}
        return result;
    }

    ;

    static final private boolean isDirectorySelectedByDirectoryName(SyncThreadWorkArea stwa, String f_dir) {
        boolean filtered = false;
        Matcher mt;

        String t_dir = f_dir;
        String n_dir = "";
        if (f_dir.startsWith("/")) t_dir = f_dir.substring(1);
        if (!t_dir.endsWith("/")) n_dir = t_dir + "/";
        else n_dir = t_dir;

//		Log.v("","by dir f_dir="+f_dir+", n_dir="+n_dir+", t_dir="+t_dir);
        if (n_dir.equals("/")) {
            //not filtered
            filtered = true;
        } else {
            if (stwa.gp.settingDebugLevel >= 2) {
                stwa.util.addDebugMsg(2, "I", "isDirectorySelectedByDirectoryName dir=" + n_dir);
            }

            Pattern[] inc = new Pattern[0];
            if (stwa.dirIncludeFilterPatternList.size() == 0) {
                // nothing filter
                filtered = true;
            } else {
                for (int i = 0; i < stwa.dirIncludeFilterPatternList.size(); i++) {
                    mt = stwa.dirIncludeFilterPatternList.get(i).matcher(n_dir);
                    if (mt.find()) {
                        inc = stwa.dirIncludeFilterArrayList.get(i);
                        String filter = "";
                        for (int j = 0; j < inc.length; j++) {
                            filter += inc[j].toString() + "/";
                        }
//                        Log.v("","inc length="+inc.length+", include pattern="+filter);
                        filtered = true;
                        break;
                    }
                }
                if (stwa.gp.settingDebugLevel >= 2)
                    stwa.util.addDebugMsg(2, "I", "isDirectorySelectedByDirectoryName Include result:" + filtered);
            }
            if (stwa.dirExcludeFilterPatternList.size() == 0) {
                //nop
            } else {
                for (int i = 0; i < stwa.dirExcludeFilterPatternList.size(); i++) {
                    mt = stwa.dirExcludeFilterPatternList.get(i).matcher(n_dir);
                    if (mt.find()) {
                        if (stwa.currentSTI.isSyncUseExtendedDirectoryFilter1()) {
                            Pattern[] exc = new Pattern[0];
                            if (stwa.dirExcludeFilterArrayList.size() > i) {
                                exc = stwa.dirExcludeFilterArrayList.get(i);
                            }
                            String filter = "";
                            for (int j = 0; j < exc.length; j++) {
                                filter += exc[j].toString() + "/";
                            }
//                            Log.v("","inc length="+inc.length+", exc length="+exc.length+", filter="+filter);
                            if (inc.length > exc.length) {
                                //Selected this entry
                            } else {
                                filtered = false;
                            }
                        } else {
                            filtered = false;
                        }
                    }
                }
                if (stwa.gp.settingDebugLevel >= 2)
                    stwa.util.addDebugMsg(2, "I", "isDirectorySelectedByDirectoryName Exclude result:" + filtered);
            }
            if (stwa.gp.settingDebugLevel >= 2)
                stwa.util.addDebugMsg(2, "I", "isDirectorySelectedByDirectoryName result:" + filtered);
        }
        return filtered;
    }

    ;

//	static final public boolean isDirectoryExcluded(SyncThreadWorkArea stwa, String fp) {
//		boolean result=false;
//
//		Matcher mt;
//
//		if (stwa.dirExcludeFilterPatternList.size()==0) {
//			//nop
//		} else {
//		    for(int i=0;i<stwa.dirExcludeFilterPatternList.size();i++) {
//                mt = stwa.dirExcludeFilterPatternList.get(i).matcher(fp);
//                if (mt.find()) {
//                    result=true;
//                }
//            }
//		}
//		if (stwa.gp.settingDebugLevel>=2) stwa.util.addDebugMsg(2,"I","isDirectoryExcluded result:"+result);
//
//		return result;
//	}

    static final public boolean isDirectoryToBeProcessed(SyncThreadWorkArea stwa, String abs_dir) {
        boolean inc = false, exc = false, result = false;

        String filter_dir = "";
        Pattern[] matched_inc_array = null;
        Pattern[] matched_exc_array = null;
        if (abs_dir.length() != 0) {
            if (stwa.dirIncludeFilterArrayList.size() > 0 || stwa.dirExcludeFilterPatternList.size() > 0) {
                if (abs_dir.endsWith("/")) filter_dir = abs_dir.substring(0, abs_dir.length() - 1);
                else filter_dir = abs_dir;
            }
            if (stwa.dirIncludeFilterArrayList.size() == 0) inc = true;
            else {
                String[] dir_array = null;
                if (filter_dir.startsWith("/")) dir_array = filter_dir.substring(1).split("/");
                else dir_array = filter_dir.split("/");
                for (int i = 0; i < stwa.dirIncludeFilterArrayList.size(); i++) {
                    Pattern[] pattern_array = stwa.dirIncludeFilterArrayList.get(i);
                    boolean found = true;
                    for (int j = 0; j < Math.min(dir_array.length, pattern_array.length); j++) {
//						Log.v("","no="+i+", pat="+pattern_array[j]+", dir="+dir_array[j]);
                        Matcher mt = pattern_array[j].matcher(dir_array[j]);
                        if (dir_array[j].length() != 0) {
                            found = mt.find();
                            if (!found) {
                                break;
                            }
                        }
                    }
                    if (found) {
                        inc = true;
                        matched_inc_array = pattern_array;
                        break;
                    }
                }
            }
            if (stwa.dirExcludeFilterPatternList.size() == 0) exc = false;
            else {
                exc = false;
//                Log.v("","exc size="+stwa.dirExcludeFilterPatternList.size());
                for (int i = 0; i < stwa.dirExcludeFilterPatternList.size(); i++) {
                    Pattern filter_pattern = stwa.dirExcludeFilterPatternList.get(i);
                    Matcher mt = filter_pattern.matcher(filter_dir);
                    if (mt.find()) {
//                        Log.v("","i="+i+", pattern="+filter_pattern.toString()+", dir="+filter_dir);
//					    Log.v("","i="+i+", array="+stwa.dirExcludeFilterArrayList.get(i)[0]);
//                        Log.v("","inc len="+matched_inc_array.length+", exc len="+stwa.dirExcludeFilterArrayList.get(i).toString());
                        if (stwa.currentSTI.isSyncUseExtendedDirectoryFilter1()) {
                            if (matched_inc_array != null) {
                                if (matched_inc_array.length > stwa.dirExcludeFilterArrayList.get(i).length) {
                                } else {
                                    exc = true;
                                    break;
                                }
                            } else {
                                exc = true;
                                break;
                            }
                        } else {
                            exc = true;
                            break;
                        }
                    }
                    if (exc) break;
                }
            }

            if (exc) result = false;
            else if (inc) result = true;
            else result = false;
        } else {
            result = true;
            inc = exc = false;
        }
        if (stwa.gp.settingDebugLevel >= 2)
            stwa.util.addDebugMsg(2, "I", "isDirectoryToBeProcessed" +
                    " include=" + inc + ", exclude=" + exc + ", result=" + result + ", dir=" + abs_dir);
        return result;
    }

    ;

    private void addPresetFileFilter(ArrayList<String> ff, String[] preset_ff) {
        for (String add_str : preset_ff) {
            boolean found = false;
            for (String ff_str : ff) {
                if (ff_str.substring(1).equals(add_str)) {
                    found = true;
                    break;
                }
            }
            if (!found) ff.add("I" + add_str);
            else if (mStwa.gp.settingDebugLevel >= 1)
                mStwa.util.addDebugMsg(1, "I", "addPresetFileFilter" + " Duplicate file filter=" + add_str);
        }
    }

    final private void compileFilter(SyncTaskItem sti, ArrayList<String> s_ff, ArrayList<String> s_df) {
        ArrayList<String> ff = new ArrayList<String>();
        ff.addAll(s_ff);
        if (sti.isSyncFileTypeAudio()) addPresetFileFilter(ff, SYNC_FILE_TYPE_AUDIO);
        if (sti.isSyncFileTypeImage()) addPresetFileFilter(ff, SYNC_FILE_TYPE_IMAGE);
        if (sti.isSyncFileTypeVideo()) addPresetFileFilter(ff, SYNC_FILE_TYPE_VIDEO);
        Collections.sort(ff);

        ArrayList<String> df = new ArrayList<String>();
        df.addAll(s_df);
        Collections.sort(df, new Comparator<String>() {
            @Override
            public int compare(String s, String t1) {
                return t1.substring(1).compareTo(s.substring(1));
            }
        });

        int flags = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;
        String ffinc = "", ffexc = "", dfinc = "", dfexc = "";
        if (ff.size() != 0) {
            String prefix, filter, cni = "", cne = "";
            for (int j = 0; j < ff.size(); j++) {
                prefix = ff.get(j).substring(0, 1);
                filter = ff.get(j).substring(1, ff.get(j).length());

                String pre_str = "";
                if (!filter.startsWith("*")) pre_str = "^";
                if (prefix.equals("I")) {
                    ffinc = ffinc + cni + pre_str + MiscUtil.convertRegExp(filter);
                    cni = "|";
                } else {
                    ffexc = ffexc + cne + pre_str + MiscUtil.convertRegExp(filter);
                    cne = "|";
                }
            }
        }
        mStwa.dirIncludeFilterArrayList.clear();
        mStwa.dirExcludeFilterArrayList.clear();
        mStwa.dirIncludeFilterPatternList.clear();
        mStwa.dirExcludeFilterPatternList.clear();
        if (df.size() != 0) {
            String prefix, filter, cni = "", cne = "";
            String all_inc = "", all_exc = "";
            for (int j = 0; j < df.size(); j++) {
                prefix = df.get(j).substring(0, 1);
                filter = df.get(j).substring(1, df.get(j).length());
                createDirFilterArrayList(prefix, filter);
                String pre_str = "", suf_str = "/";
                if (!filter.startsWith("*")) pre_str = "^";
                if (prefix.equals("I")) {
                    dfinc = pre_str + MiscUtil.convertRegExp(filter);
                    mStwa.dirIncludeFilterPatternList.add(Pattern.compile("(" + dfinc + ")", flags));
                    all_inc += dfinc + ";";
                } else {
                    dfexc = pre_str + MiscUtil.convertRegExp(filter);
                    mStwa.dirExcludeFilterPatternList.add(Pattern.compile("(" + dfexc + ")", flags));
                    all_exc += dfexc + ";";
                }
            }
            mStwa.util.addDebugMsg(1, "I", "compileFilter" + " Directory include=" + all_inc);
            mStwa.util.addDebugMsg(1, "I", "compileFilter" + " Directory exclude=" + all_exc);
        }

        mStwa.fileFilterInclude = mStwa.fileFilterExclude = null;
//		mStwa.dirFilterInclude = mStwa.dirFilterExclude = null;
        if (ffinc.length() != 0)
            mStwa.fileFilterInclude = Pattern.compile("(" + ffinc + ")", flags);
        if (ffexc.length() != 0)
            mStwa.fileFilterExclude = Pattern.compile("(" + ffexc + ")", flags);
//		if (dfinc.length() != 0) mStwa.dirFilterInclude = Pattern.compile("(" + dfinc + ")", flags);
//		if (dfexc.length() != 0) mStwa.dirFilterExclude = Pattern.compile("(" + dfexc + ")", flags);

        if (mStwa.gp.settingDebugLevel >= 1)
            mStwa.util.addDebugMsg(1, "I", "compileFilter" + " File include=" + ffinc + ", exclude=" + ffexc);

    }

    final private void createDirFilterArrayList(String prefix, String filter) {
        int flags = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;
//		String[] filter_array=filter.split("/");
        String[] filter_array = null;
        if (filter.startsWith("/")) filter_array = filter.replaceFirst("/", "").split("/");
        else filter_array = filter.split("/");

        Pattern[] pattern_array = new Pattern[filter_array.length];

        for (int k = 0; k < filter_array.length; k++)
            pattern_array[k] =
                    Pattern.compile("^" + MiscUtil.convertRegExp(filter_array[k]) + "$", flags);

        if (prefix.equals("I")) {
            mStwa.dirIncludeFilterArrayList.add(pattern_array);
            String array_item = "";
            for (int i = 0; i < pattern_array.length; i++) array_item += pattern_array[i] + "/";
            mStwa.util.addDebugMsg(1, "I", "createDirFilterArrayList" + " Directory include=" + array_item);

        } else {
            mStwa.dirExcludeFilterArrayList.add(pattern_array);
            String array_item = "";
            for (int i = 0; i < pattern_array.length; i++) array_item += pattern_array[i] + "/";
            mStwa.util.addDebugMsg(1, "I", "createDirFilterArrayList" + " Directory exclude=" + array_item);
        }
    }

    ;

    private void waitMediaScannerConnected() {
        int cnt = 100;
        while (!mStwa.mediaScanner.isConnected() && cnt > 0) {
            SystemClock.sleep(100);
            cnt--;
        }
    }

    ;

    private void prepareMediaScanner() {
        mStwa.mediaScanner = new MediaScannerConnection(mGp.appContext, new MediaScannerConnectionClient() {
            @Override
            public void onMediaScannerConnected() {
                if (mGp.settingDebugLevel >= 1)
                    mStwa.util.addDebugMsg(1, "I", "MediaScanner connected.");
            }

            ;

            @Override
            public void onScanCompleted(final String fp, final Uri uri) {
                if (mGp.settingDebugLevel >= 2)
                    mStwa.util.addDebugMsg(2, "I", "MediaScanner scan completed. fn=", fp, ", Uri=" + uri);
//				checkMediaScannerReult(fp,uri);
            }

            ;
        });
        mStwa.mediaScanner.connect();

    }

    ;

    @SuppressLint("DefaultLocale")
    static final public void scanMediaFile(SyncThreadWorkArea stwa, String fp) {
//		defaultSettingScanExternalStorage
        if (!stwa.mediaScanner.isConnected()) {
            stwa.util.addLogMsg("W", fp, "Media scanner not not invoked, because mdeia scanner was not connected.");
            return;
        }
//		String fid=(fp.lastIndexOf(".")>=0)?fp.substring(fp.lastIndexOf(".")+1):"";
//		if (!fid.equals("")) {
//			String mt=MimeTypeMap.getSingleton().getMimeTypeFromExtension(fid.toLowerCase());
//			if (stwa.gp.settingDebugLevel>=2)
//				stwa.util.addDebugMsg(2,"I","scanMediaFile ext="+fid+", mime type="+mt);
//			if (mt!=null && (mt.startsWith("audio") || mt.startsWith("image") || mt.startsWith("video") ))
//				stwa.mediaScanner.scanFile(fp, null);
//		}
        stwa.mediaScanner.scanFile(fp, null);
    }

    ;

    private String mSyncHistroryResultFilepath = null;

    final private void openSyncResultLog(SyncTaskItem sti) {
        mSyncHistroryResultFilepath = mStwa.util.createSyncResultFilePath(sti.getSyncTaskName());
        if (mStwa.syncHistoryWriter != null) closeSyncResultLog();
        File lf = new File(mGp.settingMgtFileDir + "");
        try {
            FileWriter fos = new FileWriter(mSyncHistroryResultFilepath);
            BufferedWriter bow = new BufferedWriter(fos, 1024 * 256);
            mStwa.syncHistoryWriter = new PrintWriter(bow, false);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    ;

    private void closeSyncResultLog() {
        if (mStwa.syncHistoryWriter != null) {
            final PrintWriter pw = mStwa.syncHistoryWriter;
            Thread th = new Thread() {
                @Override
                public void run() {
                    pw.flush();
                    pw.close();
                }
            };
            th.start();
            mStwa.syncHistoryWriter = null;
//			Log.v("","close exit");
        }
    }

    ;

    private void playBackDefaultNotification() {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (uri != null) {
            final MediaPlayer player = MediaPlayer.create(mGp.appContext, uri);
            if (player != null) {
                float vol = (float) mGp.settingNotificationVolume / 100.0f;
                player.setVolume(vol, vol);
                if (player != null) {
                    Thread th = new Thread() {
                        @Override
                        public void run() {
                            int dur = player.getDuration();
                            player.start();
                            SystemClock.sleep(dur + 10);
                            player.stop();
                            player.reset();
                            player.release();
                        }
                    };
                    th.setPriority(Thread.MAX_PRIORITY);
                    th.start();
                }
            } else {
                mStwa.util.addLogMsg("I", "Default notification can not playback, because default playback is not initialized.");
            }
        }
    }

    ;

    private void vibrateDefaultPattern() {
        Thread th = new Thread() {
            @SuppressWarnings("deprecation")
            @Override
            public void run() {
                Vibrator vibrator = (Vibrator) mGp.appContext.getSystemService(Context.VIBRATOR_SERVICE);
                vibrator.vibrate(new long[]{0, 200, 400, 200, 400, 200}, -1);
            }
        };
        th.start();
    }

    ;

    final private void addHistoryList(SyncTaskItem sti,
                                      int status, int copy_cnt, int del_cnt, int ignore_cnt,
                                      int retry_cnt, String error_msg, String sync_elapsed_time) {
        String date_time = StringUtil.convDateTimeTo_YearMonthDayHourMinSec(System.currentTimeMillis());
        String date = date_time.substring(0, 10);
        String time = date_time.substring(11);
        final SyncHistoryItem hli = new SyncHistoryItem();
        hli.sync_date = date;
        hli.sync_time = time;
        hli.sync_elapsed_time = sync_elapsed_time;
        hli.sync_prof = sti.getSyncTaskName();
        hli.sync_status = status;
        hli.sync_test_mode = sti.isSyncTestMode();

        hli.sync_result_no_of_copied = copy_cnt;
        hli.sync_result_no_of_deleted = del_cnt;
        hli.sync_result_no_of_ignored = ignore_cnt;
        hli.sync_result_no_of_retry = retry_cnt;
        hli.sync_req = mGp.syncThreadRequestID;
        hli.sync_error_text = error_msg;
//		if (!mGp.currentLogFilePath.equals("")) hli.isLogFileAvailable=true;
//		hli.sync_log_file_path=mGp.currentLogFilePath;
        hli.sync_result_file_path = mSyncHistroryResultFilepath;
//		Log.v("","before");
//		Log.v("","after");
        SyncTaskItem pfli = SyncTaskUtil.getSyncTaskByName(mGp.syncTaskList, sti.getSyncTaskName());
        if (pfli != null) {
            pfli.setLastSyncTime(date + " " + time);
            pfli.setLastSyncResult(status);
        }
        if (mGp.syncHistoryAdapter != null) {
            mGp.uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    mGp.syncHistoryList.add(0, hli);
                    mGp.syncHistoryAdapter.notifyDataSetChanged();
                    mStwa.util.saveHistoryList(mGp.syncHistoryList);
                }
            });
        } else {
            mGp.syncHistoryList.add(0, hli);
            mStwa.util.saveHistoryList(mGp.syncHistoryList);
        }
    }

    ;

    static final public String calTransferRate(long tb, long tt) {
        String tfs = null;
        BigDecimal bd_tr;
//		Log.v("","byte="+tb+", time="+tt);

        if (tb == 0) return "0Bytes/sec";

        long n_tt = (tt == 0) ? 1 : tt;

        if (tb > (1024)) {//KB
            BigDecimal dfs1 = new BigDecimal(tb * 1.000);
            BigDecimal dfs2 = new BigDecimal(1024 * 1.000);
            BigDecimal dfs3 = new BigDecimal("0.000000");
            dfs3 = dfs1.divide(dfs2);
            BigDecimal dft1 = new BigDecimal(n_tt * 1.000);
            BigDecimal dft2 = new BigDecimal(1000.000);
            BigDecimal dft3 = new BigDecimal("0.000000");
            dft3 = dft1.divide(dft2);
            bd_tr = dfs3.divide(dft3, 2, BigDecimal.ROUND_HALF_UP);
            tfs = bd_tr + "KBytes/sec";
//			Log.v("","dfs1="+dfs1+", dfs2="+dfs2+", dfs3="+dfs3);
//			Log.v("","dft1="+dft1+", dft2="+dft2+", dft3="+dft3);
//			Log.v("","bd_tr="+bd_tr+", tfs="+tfs);
        } else {
            BigDecimal dfs1 = new BigDecimal(tb * 1.000);
            BigDecimal dfs2 = new BigDecimal(1024 * 1.000);
            BigDecimal dfs3 = new BigDecimal("0.000000");
            dfs3 = dfs1.divide(dfs2);
            BigDecimal dft1 = new BigDecimal(n_tt * 1.000);
            BigDecimal dft2 = new BigDecimal(1000.000);
            BigDecimal dft3 = new BigDecimal("0.000000");
            dft3 = dft1.divide(dft2);
            bd_tr = dfs3.divide(dft3, 2, BigDecimal.ROUND_HALF_UP);
            tfs = bd_tr + "Bytes/sec";
//			Log.v("","dfs1="+dfs1+", dfs2="+dfs2+", dfs3="+dfs3);
//			Log.v("","dft1="+dft1+", dft2="+dft2+", dft3="+dft3);
//			Log.v("","bd_tr="+bd_tr+", tfs="+tfs);
        }

        return tfs;
    }

}