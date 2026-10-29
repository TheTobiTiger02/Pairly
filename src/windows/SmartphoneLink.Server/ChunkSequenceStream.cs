namespace SmartphoneLink.Server;

internal sealed class ChunkSequenceStream(
    IReadOnlyList<byte[]> chunks,
    Action? onDispose = null) : Stream
{
    private readonly long _length = chunks.Sum(chunk => (long)chunk.Length);
    private int _chunkIndex;
    private int _chunkOffset;
    private long _position;
    private int _disposed;

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => _length;
    public override long Position
    {
        get => _position;
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count) =>
        Read(buffer.AsSpan(offset, count));

    public override int Read(Span<byte> buffer)
    {
        var written = 0;
        while (written < buffer.Length && _chunkIndex < chunks.Count)
        {
            var chunk = chunks[_chunkIndex];
            var available = chunk.Length - _chunkOffset;
            var copyCount = Math.Min(available, buffer.Length - written);
            chunk.AsSpan(_chunkOffset, copyCount).CopyTo(buffer[written..]);
            written += copyCount;
            _chunkOffset += copyCount;
            _position += copyCount;
            if (_chunkOffset == chunk.Length)
            {
                _chunkIndex++;
                _chunkOffset = 0;
            }
        }

        return written;
    }

    public override ValueTask<int> ReadAsync(
        Memory<byte> buffer,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return ValueTask.FromResult(Read(buffer.Span));
    }

    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing && Interlocked.Exchange(ref _disposed, 1) == 0)
        {
            onDispose?.Invoke();
        }

        base.Dispose(disposing);
    }
}
