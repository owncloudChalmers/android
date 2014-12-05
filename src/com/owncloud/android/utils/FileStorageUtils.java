/* ownCloud Android client application
 *   Copyright (C) 2012-2013 ownCloud Inc.
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

package com.owncloud.android.utils;

import java.io.File;

import com.owncloud.android.MainApp;
import com.owncloud.android.R;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.lib.resources.files.RemoteFile;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.preference.PreferenceManager;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;


/**
 * Static methods to help in access to local file system.
 * 
 * @author David A. Velasco
 */
public class FileStorageUtils {
    //private static final String LOG_TAG = "FileStorageUtils";

    /**
     * Deletes the provided image file while clearing caches. Useful for deleting images where, for instance,
     * galleries would keep a thumbnail for the image in cache. If the file is not an image, it will still
     * be deleted but caches may not have been cleared.
     * @param context The context to delete the image file from
     * @param file The image file to delete
     * @return true if successful; otherwise false
     */
    public static final boolean deleteImageFile(Context context, File file){
        String projectionID = MediaStore.Images.Media._ID;
        String selection = MediaStore.Images.Media.DATA + " = ?";
        Uri queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        return FileStorageUtils.deleteMediaFile(context, file, projectionID, queryUri, selection);
    }

    /**
     * Deletes the provided video file while clearing caches. Useful for deleting videos where, for instance,
     * galleries would keep a thumbnail for the video file in cache. If the file is not a video, it will still
     * be deleted but caches may not have been cleared.
     * @param context The context to delete the video file from
     * @param file The video file to delete
     * @return true if successful; otherwise false
     */
    public static final boolean deleteVideoFile(Context context, File file){
        String projectionID = MediaStore.Video.Media._ID;
        String selection = MediaStore.Video.Media.DATA + " = ?";
        Uri queryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        return FileStorageUtils.deleteMediaFile(context, file, projectionID, queryUri, selection);
    }

    private static final boolean deleteMediaFile(Context context, File file, String projectionID, Uri styleURI, String selection){
        String[] projection = { projectionID };
        String[] selectionArgs = new String[] { file.getAbsolutePath() };

        // Match on file path
        ContentResolver contentResolver = context.getContentResolver();
        Cursor c = contentResolver.query(styleURI, projection, selection, selectionArgs, null);
        boolean result = false;
        // Check if matching is found
        if (c.moveToFirst()) {
            // Delete file
            long id = c.getLong(c.getColumnIndexOrThrow(projectionID));
            // Append id to content uri
            Uri deleteUri = ContentUris.withAppendedId(styleURI, id);
            contentResolver.delete(deleteUri, null, null);
            result = true;
        }
        c.close();

        return result;
    }

    public static final String getSavePath(String accountName) {
        File sdCard = Environment.getExternalStorageDirectory();
        return sdCard.getAbsolutePath() + "/" + MainApp.getDataFolder() + "/" + Uri.encode(accountName, "@");
        // URL encoding is an 'easy fix' to overcome that NTFS and FAT32 don't allow ":" in file names, that can be in the accountName since 0.1.190B
    }

    public static final String getDefaultSavePathFor(String accountName, OCFile file) {
        return getSavePath(accountName) + file.getRemotePath();
    }

    public static final String getTemporalPath(String accountName) {
        File sdCard = Environment.getExternalStorageDirectory();
        return sdCard.getAbsolutePath() + "/" + MainApp.getDataFolder() + "/tmp/" + Uri.encode(accountName, "@");
            // URL encoding is an 'easy fix' to overcome that NTFS and FAT32 don't allow ":" in file names, that can be in the accountName since 0.1.190B
    }

    @SuppressLint("NewApi")
    public static final long getUsableSpace(String accountName) {
        File savePath = Environment.getExternalStorageDirectory();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.GINGERBREAD) {
            return savePath.getUsableSpace();

        } else {
            StatFs stats = new StatFs(savePath.getAbsolutePath());
            return stats.getAvailableBlocks() * stats.getBlockSize();
        }

    }
    
    public static final String getLogPath()  {
        return Environment.getExternalStorageDirectory() + File.separator + MainApp.getDataFolder() + File.separator + "log";
    }

    public static String getInstantUploadFilePath(Context context, String fileName) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        String uploadPathdef = context.getString(R.string.instant_upload_path);
        String uploadPath = pref.getString("instant_upload_path", uploadPathdef);
        String value = uploadPath + OCFile.PATH_SEPARATOR +  (fileName == null ? "" : fileName);
        return value;
    }

    /**
     * Gets the composed path when video is or must be stored
     * @param context
     * @param fileName: video file name
     * @return String: video file path composed
     */
    public static String getInstantVideoUploadFilePath(Context context, String fileName) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        String uploadVideoPathdef = context.getString(R.string.instant_upload_path);
        String uploadVideoPath = pref.getString("instant_video_upload_path", uploadVideoPathdef);
        String value = uploadVideoPath + OCFile.PATH_SEPARATOR +  (fileName == null ? "" : fileName);
        return value;
    }
    
    public static String getParentPath(String remotePath) {
        String parentPath = new File(remotePath).getParent();
        parentPath = parentPath.endsWith(OCFile.PATH_SEPARATOR) ? parentPath : parentPath + OCFile.PATH_SEPARATOR;
        return parentPath;
    }
    
    /**
     * Creates and populates a new {@link OCFile} object with the data read from the server.
     * 
     * @param remote    remote file read from the server (remote file or folder).
     * @return          New OCFile instance representing the remote resource described by we.
     */
    public static OCFile fillOCFile(RemoteFile remote) {
        OCFile file = new OCFile(remote.getRemotePath());
        file.setCreationTimestamp(remote.getCreationTimestamp());
        file.setFileLength(remote.getLength());
        file.setMimetype(remote.getMimeType());
        file.setModificationTimestamp(remote.getModifiedTimestamp());
        file.setEtag(remote.getEtag());
        file.setPermissions(remote.getPermissions());
        file.setRemoteId(remote.getRemoteId());
        return file;
    }
    
    /**
     * Creates and populates a new {@link RemoteFile} object with the data read from an {@link OCFile}.
     * 
     * @param oCFile    OCFile 
     * @return          New RemoteFile instance representing the resource described by ocFile.
     */
    public static RemoteFile fillRemoteFile(OCFile ocFile){
        RemoteFile file = new RemoteFile(ocFile.getRemotePath());
        file.setCreationTimestamp(ocFile.getCreationTimestamp());
        file.setLength(ocFile.getFileLength());
        file.setMimeType(ocFile.getMimetype());
        file.setModifiedTimestamp(ocFile.getModificationTimestamp());
        file.setEtag(ocFile.getEtag());
        file.setPermissions(ocFile.getPermissions());
        file.setRemoteId(ocFile.getRemoteId());
        return file;
    }
  
}
