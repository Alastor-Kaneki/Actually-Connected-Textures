package com.alastor.ctmforge.update;

// Runs in a process Shizuku starts as the shell user (see InstallService).
interface IInstallService {
    // Installs the APK read from [apk] with `pm install`, then starts [component] again
    // (unless it's empty). Returns pm's output ("Success" when it worked).
    String install(in ParcelFileDescriptor apk, String component) = 1;

    // Shizuku calls this when the app unbinds the service.
    void destroy() = 16777114;
}
