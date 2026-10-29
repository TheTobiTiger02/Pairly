using System.Collections.Concurrent;
using Pairly.Protocol;

namespace Pairly.Server;

public sealed class CommandResultBroker
{
    private readonly ConcurrentDictionary<string, TaskCompletionSource<CommandResultPayload>> _pending = new(StringComparer.Ordinal);

    public async Task<CommandResultPayload?> WaitForAsync(
        string requestId,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        var source = new TaskCompletionSource<CommandResultPayload>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_pending.TryAdd(requestId, source))
        {
            throw new InvalidOperationException($"Duplicate command request id '{requestId}'.");
        }

        try
        {
            using var timeoutSource = new CancellationTokenSource(timeout);
            using var linkedSource = CancellationTokenSource.CreateLinkedTokenSource(timeoutSource.Token, cancellationToken);
            using var registration = linkedSource.Token.Register(() => source.TrySetResult(null!));
            return await source.Task.ConfigureAwait(false);
        }
        finally
        {
            _pending.TryRemove(requestId, out _);
        }
    }

    public bool Complete(CommandResultPayload result)
    {
        return _pending.TryRemove(result.RequestId, out var source)
            && source.TrySetResult(result);
    }
}
