namespace Pairly.Core;

/// <summary>A delivery may commit only while its session, relay order and local copy still match.</summary>
public sealed class ClipboardOrdering
{
    private long _session;
    private long _relaySequence;
    private long _localRevision;
    public readonly record struct Delivery(long Session, long RelaySequence, long LocalRevision);
    public void NewSession() { _session++; _relaySequence = 0; }
    public long LocalChanged() => ++_localRevision;
    public long LocalRevision => _localRevision;
    public long Session => _session;
    public void Published(long sequence) => _relaySequence = Math.Max(_relaySequence, sequence);
    public Delivery? Receive(long sequence)
    {
        if (sequence <= _relaySequence) return null;
        _relaySequence = sequence;
        return new Delivery(_session, sequence, _localRevision);
    }
    public bool CanApply(Delivery delivery) => delivery.Session == _session &&
        delivery.RelaySequence == _relaySequence && delivery.LocalRevision == _localRevision;
    public bool InSession(Delivery delivery) => delivery.Session == _session;
}
