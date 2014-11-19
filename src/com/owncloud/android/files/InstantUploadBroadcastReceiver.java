/* ownCloud Android client application
 *   Copyright (C) 2012  Bartek Przybylski
 *   Copyright (C) 2012-2014 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.owncloud.android.files;

import java.io.File;

import com.owncloud.android.MainApp;
import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.db.DbHandler;
import com.owncloud.android.files.services.FileUploader;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.utils.FileStorageUtils;


import android.accounts.Account;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.State;
import android.preference.PreferenceManager;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.webkit.MimeTypeMap;


public class InstantUploadBroadcastReceiver extends BroadcastReceiver {

    private static String TAG = InstantUploadBroadcastReceiver.class.getName();
    // Image action
    // Unofficial action, works for most devices but not HTC. See: https://github.com/owncloud/android/issues/6
    private static String NEW_PHOTO_ACTION_UNOFFICIAL = "com.android.camera.NEW_PICTURE";
    // Officially supported action since SDK 14: http://developer.android.com/reference/android/hardware/Camera.html#ACTION_NEW_PICTURE
    private static String NEW_PHOTO_ACTION = "android.hardware.action.NEW_PICTURE";
    // Video action
    // Officially supported action since SDK 14: http://developer.android.com/reference/android/hardware/Camera.html#ACTION_NEW_VIDEO
    private static String NEW_VIDEO_ACTION = "android.hardware.action.NEW_VIDEO";

    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log_OC.d(TAG, "Received: " + action);

        if (action.equals(android.net.ConnectivityManager.CONNECTIVITY_ACTION)) {
            handleConnectivityAction(context, intent);
        } else if (action.equals(NEW_PHOTO_ACTION_UNOFFICIAL)) {
            Log_OC.d(TAG, "UNOFFICIAL processed: com.android.camera.NEW_PICTURE");
            handleInstantUploadAction(context, intent);
        } else if(action.equals(NEW_PHOTO_ACTION)) {
            Log_OC.d(TAG, "OFFICIAL processed: android.hardware.action.NEW_PICTURE");
            handleInstantUploadAction(context, intent);
        } else if(action.equals(NEW_VIDEO_ACTION)) {
            Log_OC.d(TAG, "OFFICIAL processed: android.hardware.action.NEW_VIDEO");
            handleInstantUploadAction(context, intent);
        } else {
            Log_OC.e(TAG, "Incorrect intent sent: " + intent.getAction());
        }
    }

    private void handleInstantUploadAction(Context context, Intent intent) {
        // Get the account to associate with the upload
        // Abort if no account is available
        Account account = AccountUtils.getCurrentOwnCloudAccount(context);
        if (account == null) {
            Log_OC.w(TAG, "No ownCloud account found for instant upload, aborting");
            return;
        }

        // File information
        String[] CONTENT_PROJECTION = null;
        String data = null;

        // Check if Photo or Video
        if (isVideoAction(intent.getAction())) {
            Log_OC.w(TAG, "New video received");

            // Abort if instant video upload is disabled
            if (!instantVideoUploadEnabled(context)) {
                Log_OC.d(TAG, "Instant video upload disabled, ignoring new video");
                return;
            }

            CONTENT_PROJECTION = new String[]{ Video.Media.DATA, Video.Media.DISPLAY_NAME, Video.Media.MIME_TYPE, Video.Media.SIZE };
            data = Video.Media.DATA;
        } else if (isImageAction(intent.getAction())) {
            Log_OC.w(TAG, "New photo received");

            // Abort if instant photo upload is disabled
            if (!instantPictureUploadEnabled(context)) {
                Log_OC.d(TAG, "Instant picture upload disabled, ignoring new picture");
                return;
            }

            CONTENT_PROJECTION = new String[]{ Images.Media.DATA, Images.Media.DISPLAY_NAME, Images.Media.MIME_TYPE, Images.Media.SIZE };
            data = Images.Media.DATA;
        }

        // Create cursor and move to first row. If unable to move to the first row, abort.
        Cursor c = context.getContentResolver().query(intent.getData(), CONTENT_PROJECTION, null, null, null);
        if (!c.moveToFirst()) {
            Log_OC.e(TAG, "Couldn't resolve given uri: " + intent.getDataString());
            return;
        }

        // Get the file path for the instant upload
        String filePath = c.getString(c.getColumnIndex(data));
        c.close();
        Log_OC.d(TAG, "File path: " + filePath);

        // save always temporally the picture to upload
        DbHandler db = new DbHandler(context);
        db.putFileForLater(filePath, account.name, null);
        db.close();

        // Initiate upload attempt
        createUploadIntent(context, intent);
    }

    /**
     * Creates and starts an intent for uploading files.
     * This method aborts if there is no valid upload connection.
     *
     * @param context
     * @param intent
     */
    private void createUploadIntent(Context context, Intent intent) {
        // If there is no valid connection, abort.
        if (intent.hasExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY) || !isOnline(context)) {
            Log_OC.d(TAG, "No connectivity, abort upload.");
            return;
        } else if ((instantPictureUploadViaWiFiOnly(context) && instantVideoUploadViaWiFiOnly(context)) && !isConnectedViaWiFi(context)) {
            Log_OC.d(TAG, "No wifi-connectivity which is required, abort upload.");
            return;
        }

        // TODO: Handle Wifi-limitations, tricky given that we get not just instant upload files here.
        // (!instantPictureUploadViaWiFiOnly(context) || (instantPictureUploadViaWiFiOnly(context) == isConnectedViaWiFi(context) == true)))

        DbHandler db = new DbHandler(context);
        Cursor c = db.getAwaitingFiles();
        if (c.moveToFirst()) {
            do {
                String account_name = c.getString(c.getColumnIndex("account"));
                String file_path = c.getString(c.getColumnIndex("path"));
                File f = new File(file_path);
                if (f.exists()) {
                    Account account = new Account(account_name, MainApp.getAccountType());
                    String mimeType = null;
                    try {
                        mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                f.getName().substring(f.getName().lastIndexOf('.') + 1));

                    } catch (Throwable e) {
                        Log_OC.e(TAG, "Trying to find out MIME type of a file without extension: " + f.getName());
                    }
                    if (mimeType == null)
                        mimeType = "application/octet-stream";

                    boolean uploadImage = mimeType.startsWith("image/")
                            && instantPictureUploadEnabled(context)
                            && (isConnectedViaWiFi(context) || instantPictureUploadViaWiFiOnly(context) == false);

                    boolean uploadVideo = mimeType.startsWith("video/")
                            && instantVideoUploadEnabled(context)
                            && (isConnectedViaWiFi(context) || instantVideoUploadViaWiFiOnly(context) == false);

                    if (uploadImage || uploadVideo) {
                        Intent i = new Intent(context, FileUploader.class);
                        i.putExtra(FileUploader.KEY_ACCOUNT, account);
                        i.putExtra(FileUploader.KEY_LOCAL_FILE, file_path);
                        i.putExtra(FileUploader.KEY_REMOTE_FILE, FileStorageUtils.getInstantUploadFilePath(context, f.getName()));
                        i.putExtra(FileUploader.KEY_UPLOAD_TYPE, FileUploader.UPLOAD_SINGLE_FILE);
                        i.putExtra(FileUploader.KEY_MIME_TYPE, mimeType);
                        i.putExtra(FileUploader.KEY_INSTANT_UPLOAD, true);

                        // Intent information indicating that no local files should be stored
                        SharedPreferences pm = PreferenceManager.getDefaultSharedPreferences(context);
                        if(pm.getBoolean("instant_upload_no_local", false)){
                            i.putExtra(FileUploader.KEY_INSTANT_UPLOAD_REMOVE_ORIGINAL, true);
                            i.putExtra(FileUploader.KEY_LOCAL_BEHAVIOUR, FileUploader.LOCAL_BEHAVIOUR_FORGET);
                        }

                        context.startService(i);
                    }

                } else {
                    Log_OC.w(TAG, "Instant upload file " + f.getAbsolutePath() + " don't exist anymore");
                }
            } while (c.moveToNext());
        }
        c.close();
        db.close();
    }

    private void handleConnectivityAction(Context context, Intent intent) {
        if (!instantPictureUploadEnabled(context) && !instantVideoUploadEnabled(context)) {
            Log_OC.d(TAG, "Instant upload disabled, don't upload anything");
            return;
        }

        createUploadIntent(context, intent);
    }

    private static boolean isVideoAction(String intentAction) {
        return NEW_VIDEO_ACTION.equals(intentAction);
    }

    private static boolean isImageAction(String intentAction) {
        return NEW_PHOTO_ACTION.equals(intentAction) || NEW_PHOTO_ACTION_UNOFFICIAL.equals(intentAction);
    }

    public static boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
    }

    public static boolean isConnectedViaWiFi(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && cm.getActiveNetworkInfo() != null
                && cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_WIFI
                && cm.getActiveNetworkInfo().getState() == State.CONNECTED;
    }

    public static boolean instantPictureUploadEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_uploading", false);
    }

    public static boolean instantVideoUploadEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_video_uploading", false);
    }

    public static boolean instantPictureUploadViaWiFiOnly(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_upload_on_wifi", false);
    }
    
    public static boolean instantVideoUploadViaWiFiOnly(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("instant_video_upload_on_wifi", false);
    }
}
