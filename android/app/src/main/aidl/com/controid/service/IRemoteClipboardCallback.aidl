package com.controid.service;

import android.os.ParcelFileDescriptor;

oneway interface IRemoteClipboardCallback {
    void onPrimaryClipTextChanged(String text);
    void onPrimaryClipStreamChanged(String mime, String uriString, in ParcelFileDescriptor dataPipe);
}
