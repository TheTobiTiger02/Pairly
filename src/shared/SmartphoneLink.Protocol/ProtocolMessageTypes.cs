namespace SmartphoneLink.Protocol;

public static class ProtocolMessageTypes
{
    public const string PairHello = "pair.hello";
    public const string PairConfirm = "pair.confirm";
    public const string SessionChallenge = "session.challenge";
    public const string SessionAuthenticate = "session.authenticate";
    public const string SessionReady = "session.ready";
    public const string DeviceCapabilities = "device.capabilities";
    public const string DeviceState = "device.state";
    public const string DeviceSyncRequest = "device.sync.request";
    public const string DeviceForget = "device.forget";
    public const string DeviceForgetConfirm = "device.forget.confirm";
    public const string ClipboardItem = "clipboard.item";
    public const string ClipboardAck = "clipboard.ack";
    public const string ContentTransferStart = "content.transfer.start";
    public const string ContentTransferChunk = "content.transfer.chunk";
    public const string ContentTransferComplete = "content.transfer.complete";
    public const string MediaIndexRequest = "media.index.request";
    public const string MediaIndexPage = "media.index.page";
    public const string MediaContentRequest = "media.content.request";
    public const string MediaContentResponse = "media.content.response";
    public const string NotificationUpsert = "notification.upsert";
    public const string NotificationRemove = "notification.remove";
    public const string NotificationSnapshotBegin = "notification.snapshot.begin";
    public const string NotificationSnapshotEnd = "notification.snapshot.end";
    public const string NotificationAction = "notification.action";
    public const string SmsThreadPage = "sms.thread.page";
    public const string SmsMessagePage = "sms.message.page";
    public const string SmsSend = "sms.send";
    public const string MirrorStart = "mirror.start";
    public const string MirrorSignal = "mirror.signal";
    public const string MirrorStop = "mirror.stop";
    public const string MirrorConfigure = "mirror.configure";
    public const string MirrorTelemetry = "mirror.telemetry";
    public const string MirrorState = "mirror.state";
    public const string MirrorInput = "mirror.input";
    public const string CallRinging = "call.ringing";
    public const string CallIdle = "call.idle";
    public const string CallActive = "call.active";
    public const string CallAction = "call.action";
    public const string StorageListRequest = "storage.list.request";
    public const string StorageListResponse = "storage.list.response";
    public const string StorageFileRequest = "storage.file.request";
    public const string StorageFileResponse = "storage.file.response";
    public const string StorageDeleteRequest = "storage.delete.request";
    public const string HeartbeatPing = "heartbeat.ping";
    public const string HeartbeatPong = "heartbeat.pong";
    public const string CommandResult = "command.result";
    public const string Error = "error";
    public const string HostEndpoints = "host.endpoints";
    public const string DiagnosticsRequest = "diagnostics.request";
    public const string DiagnosticsSnapshot = "diagnostics.snapshot";
    public const string StreamOpen = "stream.open";
    public const string StreamReady = "stream.ready";
    public const string StreamClose = "stream.close";
    public const string MirrorVideoFormat = "mirror.video.format";
    public const string MirrorKeyframeRequest = "mirror.keyframe.request";
    public const string MirrorQuality = "mirror.quality";
    public const string MediaSessionState = "media.session.state";
    public const string MediaSessionCommand = "media.session.command";
    public const string FilePushStart = "file.push.start";
    public const string FilePushResult = "file.push.result";
    public const string AppsListRequest = "apps.list.request";
    public const string AppsListResponse = "apps.list.response";
    public const string AppsLaunch = "apps.launch";
    public const string AppsLaunchResult = "apps.launch.result";

    private static readonly HashSet<string> KnownTypes = new(StringComparer.Ordinal)
    {
        PairHello,
        PairConfirm,
        SessionChallenge,
        SessionAuthenticate,
        SessionReady,
        DeviceCapabilities,
        DeviceState,
        DeviceSyncRequest,
        DeviceForget,
        DeviceForgetConfirm,
        ClipboardItem,
        ClipboardAck,
        ContentTransferStart,
        ContentTransferChunk,
        ContentTransferComplete,
        MediaIndexRequest,
        MediaIndexPage,
        MediaContentRequest,
        MediaContentResponse,
        NotificationUpsert,
        NotificationRemove,
        NotificationSnapshotBegin,
        NotificationSnapshotEnd,
        NotificationAction,
        SmsThreadPage,
        SmsMessagePage,
        SmsSend,
        MirrorStart,
        MirrorSignal,
        MirrorStop,
        MirrorConfigure,
        MirrorTelemetry,
        MirrorState,
        MirrorInput,
        CallRinging,
        CallIdle,
        CallActive,
        CallAction,
        StorageListRequest,
        StorageListResponse,
        StorageFileRequest,
        StorageFileResponse,
        StorageDeleteRequest,
        HeartbeatPing,
        HeartbeatPong,
        CommandResult,
        Error,
        HostEndpoints,
        DiagnosticsRequest,
        DiagnosticsSnapshot,
        StreamOpen,
        StreamReady,
        StreamClose,
        MirrorVideoFormat,
        MirrorKeyframeRequest,
        MirrorQuality,
        MediaSessionState,
        MediaSessionCommand,
        FilePushStart,
        FilePushResult,
        AppsListRequest,
        AppsListResponse,
        AppsLaunch,
        AppsLaunchResult
    };

    public static bool IsKnown(string messageType) => KnownTypes.Contains(messageType);
}
