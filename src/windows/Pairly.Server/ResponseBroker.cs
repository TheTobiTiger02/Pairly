using System.Collections.Concurrent;

namespace Pairly.Server;

public sealed class ResponseBroker<T>
{
    private readonly ConcurrentDictionary<string, TaskCompletionSource<T>> _pending = new(StringComparer.Ordinal);

    public async Task<T?> WaitForAsync(
        string requestId,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        var source = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_pending.TryAdd(requestId, source))
        {
            throw new InvalidOperationException($"Duplicate request id '{requestId}'.");
        }

        using var timeoutCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutCancellation.CancelAfter(timeout);

        try
        {
            return await source.Task.WaitAsync(timeoutCancellation.Token);
        }
        catch (OperationCanceledException)
        {
            return default;
        }
        finally
        {
            _pending.TryRemove(requestId, out _);
        }
    }

    public bool Complete(string requestId, T response)
    {
        return _pending.TryGetValue(requestId, out var source)
            && source.TrySetResult(response);
    }
}
