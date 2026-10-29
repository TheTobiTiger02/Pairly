using System.Collections.Concurrent;
using Pairly.Protocol;

namespace Pairly.Core;

public sealed class SmsStore
{
    private readonly ConcurrentDictionary<string, ConcurrentDictionary<string, SmsThreadPayload>> _threadsByDevice = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, ConcurrentDictionary<string, ConcurrentDictionary<string, SmsMessagePayload>>> _messagesByDevice = new(StringComparer.Ordinal);

    public void UpsertThreads(string deviceId, SmsThreadPagePayload page)
    {
        var threads = _threadsByDevice.GetOrAdd(
            deviceId,
            _ => new ConcurrentDictionary<string, SmsThreadPayload>(StringComparer.Ordinal));

        foreach (var thread in page.Threads)
        {
            threads[thread.ThreadId] = thread;
        }
    }

    public void UpsertMessages(string deviceId, SmsMessagePagePayload page)
    {
        var messagesByThread = _messagesByDevice.GetOrAdd(
            deviceId,
            _ => new ConcurrentDictionary<string, ConcurrentDictionary<string, SmsMessagePayload>>(StringComparer.Ordinal));
        var messages = messagesByThread.GetOrAdd(
            page.ThreadId,
            _ => new ConcurrentDictionary<string, SmsMessagePayload>(StringComparer.Ordinal));

        foreach (var message in page.Messages)
        {
            messages[message.MessageId] = message;
        }
    }

    public IReadOnlyList<SmsThreadPayload> GetThreads(string deviceId) =>
        _threadsByDevice.TryGetValue(deviceId, out var threads)
            ? OrderThreads(threads.Values)
            : Array.Empty<SmsThreadPayload>();

    public IReadOnlyList<SmsThreadPayload> GetThreads() =>
        OrderThreads(_threadsByDevice.Values.SelectMany(threads => threads.Values));

    public IReadOnlyList<SmsMessagePayload> GetMessages(string deviceId, string threadId)
    {
        return _messagesByDevice.TryGetValue(deviceId, out var messagesByThread)
            && messagesByThread.TryGetValue(threadId, out var messages)
                ? OrderMessages(messages.Values)
                : Array.Empty<SmsMessagePayload>();
    }

    public IReadOnlyList<SmsMessagePayload> GetMessages(string threadId)
    {
        return OrderMessages(_messagesByDevice.Values
            .Where(messagesByThread => messagesByThread.TryGetValue(threadId, out _))
            .SelectMany(messagesByThread => messagesByThread[threadId].Values));
    }

    private static IReadOnlyList<SmsThreadPayload> OrderThreads(IEnumerable<SmsThreadPayload> threads) =>
        threads
            .OrderByDescending(thread => thread.UpdatedAt)
            .ToArray();

    private static IReadOnlyList<SmsMessagePayload> OrderMessages(IEnumerable<SmsMessagePayload> messages) =>
        messages.OrderBy(message => message.SentAt).ToArray();
}
