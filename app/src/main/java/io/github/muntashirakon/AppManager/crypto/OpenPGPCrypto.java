/*
 * Copyright (C) 2020 Muntashir Al-Islam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.muntashirakon.AppManager.crypto;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.AnyThread;
import androidx.annotation.UiThread;
import io.github.muntashirakon.io.ProxyFile;
import org.openintents.openpgp.IOpenPgpService2;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;
import io.github.muntashirakon.AppManager.AppManager;
import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.IOUtils;
import io.github.muntashirakon.AppManager.utils.NotificationUtils;
import io.github.muntashirakon.io.ProxyInputStream;
import io.github.muntashirakon.io.ProxyOutputStream;

public class OpenPGPCrypto implements Crypto {
    public static final String TAG = "OpenPGPCrypto";

    public static final String ACTION_OPEN_PGP_INTERACTION_BEGIN = BuildConfig.APPLICATION_ID + ".action.OPEN_PGP_INTERACTION_BEGIN";
    public static final String ACTION_OPEN_PGP_INTERACTION_END = BuildConfig.APPLICATION_ID + ".action.OPEN_PGP_INTERACTION_END";

    public static final String GPG_EXT = ".gpg";

    private OpenPgpServiceConnection service;
    private boolean successFlag, errorFlag;
    private File[] files;
    @NonNull
    private final List<File> newFiles = new ArrayList<>();
    private InputStream is;
    private OutputStream os;
    @NonNull
    private final long[] keyIds;
    private final String provider;
    private Intent lastIntent;
    private int lastMode;
    private final Context context = AppManager.getContext();
    private final Handler handler;
    private boolean isFileMode;  // Whether to en/decrypt a file than an stream
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, @NonNull Intent intent) {
            if (intent.getAction() == null) return;
            switch (intent.getAction()) {
                case ACTION_OPEN_PGP_INTERACTION_BEGIN:
                    break;
                case ACTION_OPEN_PGP_INTERACTION_END:
                    new Thread(() -> doAction(lastIntent, lastMode, false)).start();
                    break;
            }
        }
    };

    @AnyThread
    public OpenPGPCrypto(@NonNull String keyIdsStr) throws CryptoException {
        try {
            String[] keyIds = keyIdsStr.split(",");
            this.keyIds = new long[keyIds.length];
            for (int i = 0; i < keyIds.length; ++i) this.keyIds[i] = Long.parseLong(keyIds[i]);
        } catch (NumberFormatException e) {
            throw new CryptoException(e);
        }
        this.provider = (String) AppPref.get(AppPref.PrefKey.PREF_OPEN_PGP_PACKAGE_STR);
        this.handler = new Handler(Looper.getMainLooper());
        bind();
    }

    @Override
    public void close() {
        // Unbind service
        if (service != null) service.unbindFromService();
        // Unregister receiver
        context.unregisterReceiver(receiver);
    }

    @WorkerThread
    @Override
    public boolean decrypt(@NonNull File[] files) {
        Intent intent = new Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY);
        return handleFiles(intent, Cipher.DECRYPT_MODE, files);
    }

    @Override
    public void decrypt(@NonNull InputStream encryptedStream, @NonNull OutputStream unencryptedStream) throws IOException, GeneralSecurityException {
        Intent intent = new Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY);
        if (!handleStreams(intent, Cipher.DECRYPT_MODE, encryptedStream, unencryptedStream)) {
            throw new IOException("Could not decrypt stream.");
        }
    }

    @WorkerThread
    @Override
    public boolean encrypt(@NonNull File[] filesList) {
        Intent intent = new Intent(OpenPgpApi.ACTION_ENCRYPT);
        intent.putExtra(OpenPgpApi.EXTRA_KEY_IDS, keyIds);
        return handleFiles(intent, Cipher.ENCRYPT_MODE, filesList);
    }

    @Override
    public void encrypt(@NonNull InputStream unencryptedStream, @NonNull OutputStream encryptedStream) throws IOException, GeneralSecurityException {
        Intent intent = new Intent(OpenPgpApi.ACTION_ENCRYPT);
        intent.putExtra(OpenPgpApi.EXTRA_KEY_IDS, keyIds);
        if (!handleStreams(intent, Cipher.ENCRYPT_MODE, unencryptedStream, encryptedStream)) {
            throw new IOException("Could not encrypt stream.");
        }
    }

    @WorkerThread
    private boolean handleFiles(Intent intent, int mode, @NonNull File[] filesList) {
        isFileMode = true;
        if (!waitForServiceBound()) return false;
        is = null;
        os = null;
        files = filesList;
        newFiles.clear();
        lastIntent = intent;
        lastMode = mode;
        return doAction(intent, mode, true);
    }

    @WorkerThread
    private boolean handleStreams(Intent intent, int mode, @NonNull InputStream is, @NonNull OutputStream os) {
        isFileMode = false;
        if (!waitForServiceBound()) return false;
        this.is = is;
        this.os = os;
        files = new File[0];
        newFiles.clear();
        lastIntent = intent;
        lastMode = mode;
        return doAction(intent, mode, true);
    }

    @WorkerThread
    private boolean doAction(Intent intent, int mode, boolean waitForResult) {
        if (isFileMode) {
            return doActionForFiles(intent, mode, waitForResult);
        } else {
            return doActionForStream(intent, waitForResult);
        }
    }

    @WorkerThread
    private boolean doActionForFiles(Intent intent, int mode, boolean waitForResult) {
        errorFlag = false;
        if (files.length > 0) {  // files is never null here
            for (File file : files) {
                File outputFilename;
                if (mode == Cipher.DECRYPT_MODE) {
                    outputFilename = new ProxyFile(file.getAbsolutePath().substring(0, file.getAbsolutePath().lastIndexOf(GPG_EXT)));
                } else outputFilename = new ProxyFile(file.getAbsolutePath() + GPG_EXT);
                newFiles.add(outputFilename);
                Log.i(TAG, "Input: " + file + "\nOutput: " + outputFilename);
                try {
                    InputStream is = new ProxyInputStream(file);
                    OutputStream os = new ProxyOutputStream(outputFilename);
                    OpenPgpApi api = new OpenPgpApi(context, service.getService());
                    Intent result = api.executeApi(intent, is, os);
                    handler.post(() -> handleResult(result));
                    if (waitForResult) waitForResult();
                    if (errorFlag) {
                        IOUtils.deleteSilently(outputFilename);
                        return false;
                    }
                } catch (IOException | RemoteException e) {
                    Log.e(TAG, "Error: " + e.toString(), e);
                    return false;
                }
                // Delete unencrypted file
                if (mode == Cipher.ENCRYPT_MODE) {
                    if (!file.delete()) {
                        Log.e(TAG, "Couldn't delete old file " + file);
                        return false;
                    }
                }
            }
            // Total success
        } else {
            Log.d(TAG, "No files to de/encrypt");
        }
        return true;
    }

    @WorkerThread
    private boolean doActionForStream(Intent intent, boolean waitForResult) {
        errorFlag = false;
        OpenPgpApi api = new OpenPgpApi(context, service.getService());
        Intent result = api.executeApi(intent, is, os);
        handler.post(() -> handleResult(result));
        if (waitForResult) waitForResult();
        return !errorFlag;
    }

    @NonNull
    @Override
    public File[] getNewFiles() {
        return newFiles.toArray(new File[0]);
    }

    private void bind() {
        service = new OpenPgpServiceConnection(context, provider,
                new OpenPgpServiceConnection.OnBound() {
                    @Override
                    public void onBound(IOpenPgpService2 service) {
                        Log.i(OpenPgpApi.TAG, "Service bound.");
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(OpenPgpApi.TAG, "Exception on binding.", e);
                    }
                }
        );
        service.bindToService();
        // Start broadcast receiver
        IntentFilter filter = new IntentFilter(ACTION_OPEN_PGP_INTERACTION_BEGIN);
        filter.addAction(ACTION_OPEN_PGP_INTERACTION_END);
        context.registerReceiver(receiver, filter);
    }

    @WorkerThread
    private boolean waitForServiceBound() {
        int i = 0;
        while (service.getService() == null) {
            try {
                if (i % 20 == 0)
                    Log.i(TAG, "Waiting for openpgp-api service to be bound");
                Thread.sleep(100);
                if (i > 1000)
                    break;
                i++;
            } catch (InterruptedException e) {
                Log.e(TAG, "WaitForServiceBound: interrupted", e);
                Thread.currentThread().interrupt();
            }
        }
        return service.getService() != null;
    }

    @WorkerThread
    private void waitForResult() {
        try {
            int i = 0;
            while (!successFlag && !errorFlag) {
                if (i % 200 == 0) Log.i(TAG, "Waiting for user interaction");
                Thread.sleep(100);
                if (i > 1000)
                    break;
                i++;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "waitForResult: interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    @UiThread
    private void handleResult(@NonNull Intent result) {
        successFlag = false;
        switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            case OpenPgpApi.RESULT_CODE_SUCCESS:
                Log.i(TAG, "en/decryption successful.");
                successFlag = true;
                break;
            case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                Log.i(TAG, "User interaction required. Sending intent...");
                Intent broadcastIntent = new Intent(OpenPGPCrypto.ACTION_OPEN_PGP_INTERACTION_BEGIN);
                context.sendBroadcast(broadcastIntent);
                // Intent wrapper
                Intent intent = new Intent(context, OpenPGPCryptoActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(OpenPgpApi.RESULT_INTENT, (PendingIntent) result.getParcelableExtra(OpenPgpApi.RESULT_INTENT));
                String openPGP = "Open PGP";
                // We don't need a delete intent since the time will be expired anyway
                NotificationCompat.Builder builder = NotificationUtils.getHighPriorityNotificationBuilder(context)
                        .setAutoCancel(true)
                        .setDefaults(Notification.DEFAULT_ALL)
                        .setWhen(System.currentTimeMillis())
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setTicker(openPGP)
                        .setContentTitle(openPGP)
                        .setSubText(openPGP)
                        .setContentText(context.getString(R.string.allow_open_pgp_operation));
                builder.setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT));
                NotificationUtils.displayHighPriorityNotification(context, builder.build());
                break;
            case OpenPgpApi.RESULT_CODE_ERROR:
                errorFlag = true;
                OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                if (error != null) {
                    Log.e(TAG, "handleResult: (" + error.getErrorId() + ") " + error.getMessage());
                } else Log.e(TAG, "handleResult: Error occurred during en/decryption process");
                break;
        }
    }
}
