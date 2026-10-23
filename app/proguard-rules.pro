-dontwarn javax.lang.model.**
# TunnelBackendProvider.updatePeers reads these by name (mesh direct paths)
-keepclassmembers class com.wgtunnel.backend.TunnelBackend {
    private com.wgtunnel.backend.TunnelEngine engine;
    private java.util.concurrent.ConcurrentHashMap byTunnelId;
}
-keepclassmembers class com.wgtunnel.backend.WireGuardTunnelEngine {
    public java.lang.Object updatePeers(int, com.wgtunnel.backend.model.BackendMode, java.util.List, kotlin.coroutines.Continuation);
}
