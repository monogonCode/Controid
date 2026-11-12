package com.controid.service;

import android.os.ParcelFileDescriptor;
import com.controid.service.IRemoteStreamCallback;

interface IRemoteStreamService {
    void startStream(
        int maxFps,
        int bitrateBps,
        String codecMime,
        in ParcelFileDescriptor outputPipe,
        IRemoteStreamCallback callback
    );
    void notifyHeartbeat();
    void stopStream();
    void destroy();
    void injectTouch(int action, int pointerId, int buttons, float xNorm, float yNorm);
    void injectScroll(float xNorm, float yNorm, float hScroll, float vScroll);
    void injectKey(int action, int keyCode, int metaState, int repeat);
    void injectText(String text);
}
