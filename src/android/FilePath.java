package com.vascoferreira.cordova.filepath;


import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.database.Cursor;
import android.os.Environment;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.File;
import java.util.Calendar;
import java.io.ByteArrayInputStream;
import java.util.Objects;

public class FilePath extends CordovaPlugin {

    private static final String TAG = "[FilePath plugin]: ";

    private static final int INVALID_ACTION_ERROR_CODE = -1;

    private static final int GET_PATH_ERROR_CODE = 0;
    private static final String GET_PATH_ERROR_ID = null;

    private static final int GET_CLOUD_PATH_ERROR_CODE = 1;
    private static final String GET_CLOUD_PATH_ERROR_ID = "cloud";

    private static CallbackContext callback;
    private static String uriStr;
    
    public static final int READ_REQ_CODE = 0;
    
    public static final String READ = Manifest.permission.READ_EXTERNAL_STORAGE;
    
    protected void getReadPermission() {
        PermissionHelper.requestPermission(this, FilePath.READ_REQ_CODE, READ);
    }

    public void initialize(CordovaInterface cordova, final CordovaWebView webView) {
        super.initialize(cordova, webView);
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action        The action to execute.
     * @param args          JSONArry of arguments for the plugin.
     * @param callbackContext The callback context through which to return stuff to caller.
     * @return              A PluginResult object with a status and message.
     */
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("resolveNativePath")) {
            callback = callbackContext;
            uriStr = args.getString(0);

            if (PermissionHelper.hasPermission(this, READ)) {
                cordova.getThreadPool().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            resolveNativePath(callbackContext, args.getString(0));
                        } catch (JSONException e) {
                            callbackContext.error(e.getMessage());
                        }
                    }
                });
            }
            else {
                getReadPermission();
            }

            return true;
        }
        else {
            JSONObject resultObj = new JSONObject();
            
            resultObj.put("code", INVALID_ACTION_ERROR_CODE);
            resultObj.put("message", "Invalid action.");

            callbackContext.error(resultObj);
        }

        return false;
    }
    
    public void resolveNativePath(CallbackContext callback, String uriStr) throws JSONException {
        JSONObject resultObj = new JSONObject();
        /* content:///... */
        Uri pvUrl = Uri.parse(uriStr);

        Log.d(TAG, "URI: " + uriStr);

        Context appContext = this.cordova.getActivity().getApplicationContext();
        String filePath = getPath(appContext, pvUrl);

        //check result; send error/success callback
        if (Objects.equals(filePath, GET_PATH_ERROR_ID)) {
            resultObj.put("code", GET_PATH_ERROR_CODE);
            resultObj.put("message", "Unable to resolve filesystem path.");

            callback.error(resultObj);
        }
        else if (filePath.equals(GET_CLOUD_PATH_ERROR_ID)) {
            resultObj.put("code", GET_CLOUD_PATH_ERROR_CODE);
            resultObj.put("message", "Files from cloud cannot be resolved to filesystem, download is required.");

            callback.error(resultObj);
        }
        else {
            Log.d(TAG, "Filepath: " + filePath);

            callback.success("file://" + filePath);
        }
    }
    
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        for (int r:grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                JSONObject resultObj = new JSONObject();
                resultObj.put("code", 3);
                resultObj.put("message", "Filesystem permission was denied.");
                
                callback.error(resultObj);
                return;
            }
        }
        
        if (requestCode == READ_REQ_CODE) {
            resolveNativePath(callback, uriStr);
        }
    }


    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    private static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    private static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    private static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @param selection (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    private static String getDataColumn(Context context, Uri uri, String selection,
                                        String[] selectionArgs) {

        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }

    /**
     * Check if a file exists on device
     *
     * @param filePath The absolute file path
     */
    private static boolean fileExists(String filePath) {
        File file = new File(filePath);

        return file.exists();
    }

    /**
     * Get full file path from external storage
     *
     * @param pathData The storage type and the relative path
     */
    private static String getPathFromExtSD(String[] pathData) {
        final String type = pathData[0];
        final String relativePath = "/" + pathData[1];
        String fullPath;

        // on my Sony devices (4.4.4 & 5.1.1), `type` is a dynamic string
        // something like "71F8-2C0A", some kind of unique id per storage
        // don't know any API that can get the root path of that storage based on its id.
        //
        // so no "primary" type, but let the check here for other devices
        if ("primary".equalsIgnoreCase(type)) {
            fullPath = Environment.getExternalStorageDirectory() + relativePath;
            if (fileExists(fullPath)) {
                return fullPath;
            }
        }

        // Environment.isExternalStorageRemovable() is `true` for external and internal storage
        // so we cannot relay on it.
        //
        // instead, for each possible path, check if file exists
        // we'll start with secondary storage as this could be our (physically) removable sd card
        fullPath = System.getenv("SECONDARY_STORAGE") + relativePath;
        if (fileExists(fullPath)) {
            return fullPath;
        }

        fullPath = System.getenv("EXTERNAL_STORAGE") + relativePath;
        if (fileExists(fullPath)) {
            return fullPath;
        }

        return fullPath;
    }

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.<br>
     * <br>
     * Callers should check whether the path is local before assuming it
     * represents a local file.
     *
     * @param context The context.
     * @param uri The Uri to query.
     */
    private static String getPath(final Context context, final Uri uri) {

        Log.d(TAG, "File - " +
                "Authority: " + uri.getAuthority() +
                ", Fragment: " + uri.getFragment() +
                ", Port: " + uri.getPort() +
                ", Query: " + uri.getQuery() +
                ", Scheme: " + uri.getScheme() +
                ", Host: " + uri.getHost() +
                ", Segments: " + uri.getPathSegments().toString()
        );

        // DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            final String docId = DocumentsContract.getDocumentId(uri);
            Log.d(TAG, "DocumentId: " + docId);


            String path = copyFileToInternal(uri, context);
            if (path != null) {
                return path;
            }

            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                final String[] split = docId.split(":");
                String fullPath = getPathFromExtSD(split);
                if (!fullPath.equals("")) {
                    return fullPath;
                }
                else {
                    return null;
                }
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.parseLong(docId));

                return getDataColumn(context, contentUri, null, null);
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[] {
                        split[1]
                };

                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {
            if (uri.toString().contains("contact") &&  uri.toString().endsWith("photo")) {
                return getContactPhotoDriveFilePath(uri, context);
            }
            return copyFileToInternal(uri,context);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        return null;
    }

    private static String getContactPhotoDriveFilePath(Uri uri,Context context){
        Cursor returnCursor = context.getContentResolver().query(uri, new String[] {ContactsContract.Contacts.Photo.PHOTO}, null, null, null);
        int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        returnCursor.moveToFirst();
        byte[] data = returnCursor.getBlob(0);
        String name = "tempFile" + Calendar.getInstance().getTimeInMillis();
        if (nameIndex>=0) {
            name = (returnCursor.getString(nameIndex));
        }
        File file = new File(context.getCacheDir(),name);
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            FileOutputStream outputStream = new FileOutputStream(file);
            int read;
            int maxBufferSize = 1024 * 1024;
            int  bytesAvailable = inputStream.available();

            //int bufferSize = 1024;
            int bufferSize = Math.min(bytesAvailable, maxBufferSize);

            final byte[] buffers = new byte[bufferSize];
            while ((read = inputStream.read(buffers)) != -1) {
                outputStream.write(buffers, 0, read);
            }
            Log.e("File Size","Size " + file.length());
            inputStream.close();
            outputStream.close();
            Log.e("File Path","Path " + file.getPath());
            Log.e("File Size","Size " + file.length());
        }catch (Exception e){
            Log.e("Exception",e.getMessage());
        }
        returnCursor.close();
        return  file.getPath();
    }

    private static String copyFileToInternal(Uri uri, Context context){
        Cursor returnCursor = context.getContentResolver().query(uri, null, null, null, null);
        /*
        * Get the column indexes of the data in the Cursor,
        *     * move to the first row in the Cursor, get the data,
        *     * and display it.
        * */
        int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        returnCursor.moveToFirst();
        String name = (returnCursor.getString(nameIndex));
        File   file = new File(context.getCacheDir(),name);
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            FileOutputStream outputStream = new FileOutputStream(file);
            int read;
            int maxBufferSize = 1024 * 1024;
            int  bytesAvailable = inputStream.available();

            //int bufferSize = 1024;
            int bufferSize = Math.min(bytesAvailable, maxBufferSize);

            final byte[] buffers = new byte[bufferSize];
            while ((read = inputStream.read(buffers)) != -1) {
                outputStream.write(buffers, 0, read);
            }
            inputStream.close();
            outputStream.close();
            return  file.getPath();
        } catch (Exception e){
            Log.e(TAG,e.getMessage());
        }
        returnCursor.close();
        return null;
    }
}
