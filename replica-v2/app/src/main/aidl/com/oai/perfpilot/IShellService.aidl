package com.oai.perfpilot;

interface IShellService {
    String execute(String command);
    int remoteUid();
    void destroy();
}
