package com.controid.service;

interface IRemoteTetheringCallback {
    void onStatusChanged(int statusCode, String detail);
    void onClientsChanged(int clientCount);
}
