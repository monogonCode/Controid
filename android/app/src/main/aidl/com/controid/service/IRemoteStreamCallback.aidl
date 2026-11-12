package com.controid.service;

interface IRemoteStreamCallback {
    void onStreamReady(int width, int height);
    void onStreamError(int code, String message);
    void onStreamStopped();
}
