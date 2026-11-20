package com.example.procpulse.core.bridge;

interface ITelemetryUserService {
    void destroy() = 16777114;
    String readSystemMetricsSnapshot() = 1;
    String listProcessesSnapshot(int topLimit) = 2;
    String readProcessDetail(int pid, String packageName) = 3;
    String readSocketsSnapshot(int filterUid) = 4;
    String readLogcatSnapshot(int pid, int maxLines) = 5;
    String readSurfaceFlingerFps() = 6;
    int executeCommand(String command) = 7;
    String readLagDiagnosticsSnapshot(boolean includeSlowDumpsys, String activePackage, int timestatsAction) = 8;
}
