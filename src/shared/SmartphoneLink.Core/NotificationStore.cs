using System.Collections.Concurrent;
using SmartphoneLink.Protocol;

namespace SmartphoneLink.Core;

public sealed class NotificationStore
{
    private readonly ConcurrentDictionary<string, ConcurrentDictionary<string, NotificationPayload>> _notificationsByDevice = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, SnapshotState> _snapshotsByDevice = new(StringComparer.Ordinal);

    public void BeginSnapshot(string deviceId, NotificationSnapshotPayload snapshot)
    {
        if (string.IsNullOrWhiteSpace(deviceId) || string.IsNullOrWhiteSpace(snapshot.SnapshotId))
        {
            return;
        }

        _snapshotsByDevice[deviceId] = new SnapshotState(snapshot.SnapshotId);
    }

    public void Upsert(string deviceId, NotificationPayload notification)
    {
        var notifications = _notificationsByDevice.GetOrAdd(
            deviceId,
            _ => new ConcurrentDictionary<string, NotificationPayload>(StringComparer.Ordinal));
        notifications[notification.NotificationId] = notification;

        if (_snapshotsByDevice.TryGetValue(deviceId, out var snapshot)
            && (string.IsNullOrWhiteSpace(notification.SnapshotId)
                || string.Equals(notification.SnapshotId, snapshot.SnapshotId, StringComparison.Ordinal)))
        {
            snapshot.SeenNotificationIds[notification.NotificationId] = 0;
        }
    }

    public int EndSnapshot(string deviceId, NotificationSnapshotPayload snapshot)
    {
        if (!_snapshotsByDevice.TryGetValue(deviceId, out var state)
            || !string.Equals(state.SnapshotId, snapshot.SnapshotId, StringComparison.Ordinal)
            || !_snapshotsByDevice.TryRemove(
                new KeyValuePair<string, SnapshotState>(deviceId, state)))
        {
            return 0;
        }

        if (!_notificationsByDevice.TryGetValue(deviceId, out var notifications))
        {
            return 0;
        }

        var removed = 0;
        foreach (var notificationId in notifications.Keys)
        {
            if (!state.SeenNotificationIds.ContainsKey(notificationId)
                && notifications.TryRemove(notificationId, out _))
            {
                removed++;
            }
        }

        return removed;
    }

    public bool Remove(string deviceId, string notificationId) =>
        _notificationsByDevice.TryGetValue(deviceId, out var notifications)
        && notifications.TryRemove(notificationId, out _);

    public int Clear(string deviceId)
    {
        _snapshotsByDevice.TryRemove(deviceId, out _);
        if (!_notificationsByDevice.TryRemove(deviceId, out var notifications))
        {
            return 0;
        }

        return notifications.Count;
    }

    public IReadOnlyList<NotificationPayload> GetActive(string deviceId) =>
        _notificationsByDevice.TryGetValue(deviceId, out var notifications)
            ? Order(notifications.Values)
            : Array.Empty<NotificationPayload>();

    public IReadOnlyList<NotificationPayload> GetActive() =>
        Order(_notificationsByDevice.Values.SelectMany(notifications => notifications.Values));

    private static IReadOnlyList<NotificationPayload> Order(IEnumerable<NotificationPayload> notifications) =>
        notifications
            .OrderByDescending(notification => notification.PostedAt)
            .ThenBy(notification => notification.AppName, StringComparer.OrdinalIgnoreCase)
            .ToArray();

    private sealed class SnapshotState(string snapshotId)
    {
        public string SnapshotId { get; } = snapshotId;
        public ConcurrentDictionary<string, byte> SeenNotificationIds { get; } = new(StringComparer.Ordinal);
    }
}
