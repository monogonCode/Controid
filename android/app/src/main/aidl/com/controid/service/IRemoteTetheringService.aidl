package com.controid.service;

import android.os.ParcelFileDescriptor;
import com.controid.service.IRemoteClipboardCallback;
import com.controid.service.IRemoteTetheringCallback;

interface IRemoteTetheringService {
    void startWifiTethering(boolean prefer5Ghz, IRemoteTetheringCallback callback);
    void stopWifiTethering();
    int grantClipboardBackgroundPermission(String packageName);
    void registerClipboardCallback(IRemoteClipboardCallback callback);
    void unregisterClipboardCallback(IRemoteClipboardCallback callback);
    String readPrimaryClipboardText();
    ParcelFileDescriptor readPrimaryClipboardStream(out String[] outMeta);
    byte[] handleFileRpc(in byte[] request);
    void destroy();
}
