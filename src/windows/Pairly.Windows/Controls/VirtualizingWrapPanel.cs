using System.Collections.Specialized;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using Point = System.Windows.Point;
using Size = System.Windows.Size;

namespace Pairly.Windows.Controls;

/// <summary>An item that takes a full-width row of its own in a <see cref="VirtualizingWrapPanel"/>.</summary>
public interface ITileSectionHeader
{
    bool IsSectionHeader { get; }
}

/// <summary>
/// A virtualizing grid of square tiles. Columns are as many <see cref="ItemSize"/> tiles as fit, then
/// stretched to fill the width, so the grid always reaches both edges. Items that are an
/// <see cref="ITileSectionHeader"/> start a new row and span it (month headings in the photo grid).
/// Only the rows in view (plus a few either side) have containers, which keeps a photo library of
/// thousands of thumbnails cheap. Use with VirtualizingPanel.VirtualizationMode="Standard".
/// The mouse wheel glides to its target instead of jumping, and a scroll step that needs no new
/// tiles only moves the ones that are there.
/// </summary>
public sealed class VirtualizingWrapPanel : VirtualizingPanel, IScrollInfo
{
    public static readonly DependencyProperty ItemSizeProperty = DependencyProperty.Register(
        nameof(ItemSize), typeof(double), typeof(VirtualizingWrapPanel),
        new FrameworkPropertyMetadata(176.0, FrameworkPropertyMetadataOptions.AffectsMeasure));

    private const double LineStep = 48;
    private const double HeaderHeight = 44;
    private const int HeaderColumn = -1;
    // Rows kept ready beyond the viewport, so a scroll step rarely has to build tiles.
    private const double BufferRows = 2;
    // How quickly a wheel scroll closes the distance to its target (seconds to cover about 63 %).
    private const double GlideSeconds = 0.07;

    private double _glideTarget;
    private bool _gliding;
    private TimeSpan _lastFrame;
    private int _realizedFirst = -1;
    private int _realizedLast = -1;

    private Size _extent;
    private Size _viewport;
    private Point _offset;
    private int _columns = 1;
    private double _tileSize = 176;

    // Where every item goes: its top edge and its column (HeaderColumn for a full-width heading).
    // Rebuilt when the items, the column count or the tile size change, not on every scroll step.
    private double[] _tops = Array.Empty<double>();
    private int[] _itemColumns = Array.Empty<int>();
    private int _layoutCount;
    private bool _layoutDirty = true;
    private int _layoutColumns;
    private double _layoutTileSize;

    /// <summary>Preferred tile edge; the real edge grows until the row is filled.</summary>
    public double ItemSize
    {
        get => (double)GetValue(ItemSizeProperty);
        set => SetValue(ItemSizeProperty, value);
    }

    protected override Size MeasureOverride(Size availableSize)
    {
        var owner = ItemsControl.GetItemsOwner(this);
        var itemCount = owner?.Items.Count ?? 0;
        var width = double.IsInfinity(availableSize.Width) ? ItemSize : availableSize.Width;
        var height = double.IsInfinity(availableSize.Height) ? ItemSize : availableSize.Height;

        _columns = Math.Max(1, (int)(width / Math.Max(1, ItemSize)));
        _tileSize = width / _columns;
        var contentHeight = BuildLayout(owner, itemCount);
        UpdateScrollInfo(new Size(width, contentHeight), new Size(width, height));

        // Touching InternalChildren first makes sure the generator is ready.
        var children = InternalChildren;
        var generator = ItemContainerGenerator;
        if (itemCount == 0 || generator is null)
        {
            _realizedFirst = _realizedLast = -1;
            CleanUpItems(0, -1);
            return new Size(width, height);
        }

        var (firstIndex, lastIndex) = RangeFor(_offset.Y, height);
        _realizedFirst = firstIndex;
        _realizedLast = lastIndex;

        var startPosition = generator.GeneratorPositionFromIndex(firstIndex);
        var childIndex = startPosition.Offset == 0 ? startPosition.Index : startPosition.Index + 1;
        using (generator.StartAt(startPosition, GeneratorDirection.Forward, true))
        {
            for (var itemIndex = firstIndex; itemIndex <= lastIndex; itemIndex++, childIndex++)
            {
                if (generator.GenerateNext(out var newlyRealized) is not UIElement child)
                {
                    break;
                }

                if (newlyRealized)
                {
                    if (childIndex >= children.Count)
                    {
                        AddInternalChild(child);
                    }
                    else
                    {
                        InsertInternalChild(childIndex, child);
                    }

                    generator.PrepareItemContainer(child);
                }

                child.Measure(SizeOf(itemIndex, width));
            }
        }

        CleanUpItems(firstIndex, lastIndex);
        return new Size(width, height);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        var generator = ItemContainerGenerator;
        for (var childIndex = 0; childIndex < InternalChildren.Count; childIndex++)
        {
            var itemIndex = generator?.IndexFromGeneratorPosition(new GeneratorPosition(childIndex, 0)) ?? childIndex;
            if (itemIndex < 0 || itemIndex >= _layoutCount)
            {
                continue;
            }

            var column = _itemColumns[itemIndex];
            var size = SizeOf(itemIndex, finalSize.Width);
            InternalChildren[childIndex].Arrange(new Rect(
                column == HeaderColumn ? 0 : column * _tileSize, _tops[itemIndex] - _offset.Y, size.Width, size.Height));
        }

        return finalSize;
    }

    protected override void OnItemsChanged(object sender, ItemsChangedEventArgs args)
    {
        _layoutDirty = true;
        switch (args.Action)
        {
            case NotifyCollectionChangedAction.Remove:
            case NotifyCollectionChangedAction.Replace:
            case NotifyCollectionChangedAction.Move:
                RemoveInternalChildRange(args.Position.Index, args.ItemUICount);
                break;
            case NotifyCollectionChangedAction.Reset:
                SetVerticalOffset(0);
                break;
        }

        base.OnItemsChanged(sender, args);
    }

    protected override void BringIndexIntoView(int index)
    {
        if (index < 0 || index >= _layoutCount)
        {
            return;
        }

        var top = _tops[index];
        var bottom = top + HeightOf(index);
        if (top < _offset.Y)
        {
            SetVerticalOffset(top);
        }
        else if (bottom > _offset.Y + _viewport.Height)
        {
            SetVerticalOffset(bottom - _viewport.Height);
        }
    }

    /// <summary>The item index whose row is at the top of the viewport, or -1 when empty.</summary>
    public int FirstVisibleIndex => _layoutCount == 0 ? -1 : FirstIndexEndingAfter(_offset.Y);

    private double BuildLayout(ItemsControl? owner, int itemCount)
    {
        if (!_layoutDirty && itemCount == _layoutCount && _columns == _layoutColumns && Math.Abs(_tileSize - _layoutTileSize) < 0.01)
        {
            return _extent.Height;
        }

        if (_tops.Length < itemCount)
        {
            _tops = new double[itemCount];
            _itemColumns = new int[itemCount];
        }

        double y = 0;
        var column = 0;
        for (var index = 0; index < itemCount; index++)
        {
            if (owner!.Items[index] is ITileSectionHeader { IsSectionHeader: true })
            {
                if (column > 0)
                {
                    y += _tileSize;
                    column = 0;
                }

                _tops[index] = y;
                _itemColumns[index] = HeaderColumn;
                y += HeaderHeight;
                continue;
            }

            _tops[index] = y;
            _itemColumns[index] = column;
            if (++column == _columns)
            {
                column = 0;
                y += _tileSize;
            }
        }

        if (column > 0)
        {
            y += _tileSize;
        }

        _layoutCount = itemCount;
        _layoutColumns = _columns;
        _layoutTileSize = _tileSize;
        _layoutDirty = false;
        return y;
    }

    /// <summary>The items that need a container when the viewport starts at <paramref name="offset"/>.</summary>
    private (int First, int Last) RangeFor(double offset, double viewportHeight)
    {
        var buffer = _tileSize * BufferRows;
        var first = FirstIndexEndingAfter(offset - buffer);
        return (first, Math.Max(first, LastIndexStartingBefore(offset + viewportHeight + buffer)));
    }

    private double HeightOf(int index) => _itemColumns[index] == HeaderColumn ? HeaderHeight : _tileSize;

    private Size SizeOf(int index, double width) => index < _layoutCount && _itemColumns[index] == HeaderColumn
        ? new Size(width, HeaderHeight)
        : new Size(_tileSize, _tileSize);

    // Tops never decrease along the items, so both lookups are binary searches.
    private int FirstIndexEndingAfter(double y)
    {
        int low = 0, high = _layoutCount - 1;
        while (low < high)
        {
            var middle = (low + high) / 2;
            if (_tops[middle] + HeightOf(middle) > y)
            {
                high = middle;
            }
            else
            {
                low = middle + 1;
            }
        }

        return low;
    }

    private int LastIndexStartingBefore(double y)
    {
        int low = 0, high = _layoutCount - 1;
        while (low < high)
        {
            var middle = (low + high + 1) / 2;
            if (_tops[middle] < y)
            {
                low = middle;
            }
            else
            {
                high = middle - 1;
            }
        }

        return low;
    }

    private void CleanUpItems(int firstIndex, int lastIndex)
    {
        var children = InternalChildren;
        var generator = ItemContainerGenerator;
        if (generator is null)
        {
            return;
        }

        for (var childIndex = children.Count - 1; childIndex >= 0; childIndex--)
        {
            var position = new GeneratorPosition(childIndex, 0);
            var itemIndex = generator.IndexFromGeneratorPosition(position);
            if (itemIndex < firstIndex || itemIndex > lastIndex)
            {
                generator.Remove(position, 1);
                RemoveInternalChildRange(childIndex, 1);
            }
        }
    }

    private void UpdateScrollInfo(Size extent, Size viewport)
    {
        var changed = extent != _extent || viewport != _viewport;
        _extent = extent;
        _viewport = viewport;
        var maxOffset = Math.Max(0, _extent.Height - _viewport.Height);
        if (_offset.Y > maxOffset)
        {
            _offset.Y = maxOffset;
            changed = true;
        }

        if (changed)
        {
            ScrollOwner?.InvalidateScrollInfo();
        }
    }

    // IScrollInfo: vertical scrolling only; the grid always fits the width.
    public bool CanVerticallyScroll { get; set; }
    public bool CanHorizontallyScroll { get; set; }
    public double ExtentWidth => _extent.Width;
    public double ExtentHeight => _extent.Height;
    public double ViewportWidth => _viewport.Width;
    public double ViewportHeight => _viewport.Height;
    public double HorizontalOffset => 0;
    public double VerticalOffset => _offset.Y;
    public ScrollViewer? ScrollOwner { get; set; }

    public void LineUp() => SetVerticalOffset(_offset.Y - LineStep);
    public void LineDown() => SetVerticalOffset(_offset.Y + LineStep);
    public void PageUp() => SetVerticalOffset(_offset.Y - _viewport.Height);
    public void PageDown() => SetVerticalOffset(_offset.Y + _viewport.Height);
    public void MouseWheelUp() => GlideBy(-LineStep * SystemParameters.WheelScrollLines / 1.5);
    public void MouseWheelDown() => GlideBy(LineStep * SystemParameters.WheelScrollLines / 1.5);
    public void LineLeft() { }
    public void LineRight() { }
    public void PageLeft() { }
    public void PageRight() { }
    public void MouseWheelLeft() { }
    public void MouseWheelRight() { }
    public void SetHorizontalOffset(double offset) { }

    /// <summary>Jumps to the offset. Anything but the wheel (scroll bar, keys, bring into view) ends a glide.</summary>
    public void SetVerticalOffset(double offset)
    {
        StopGlide();
        MoveTo(offset);
    }

    private void MoveTo(double offset)
    {
        offset = Math.Clamp(offset, 0, Math.Max(0, _extent.Height - _viewport.Height));
        if (Math.Abs(offset - _offset.Y) < 0.1)
        {
            return;
        }

        _offset.Y = offset;
        ScrollOwner?.InvalidateScrollInfo();
        // Building tiles is the expensive part; moving the ones that exist is cheap.
        if (_layoutCount > 0 && !_layoutDirty && RangeFor(offset, _viewport.Height) == (_realizedFirst, _realizedLast))
        {
            InvalidateArrange();
        }
        else
        {
            InvalidateMeasure();
        }
    }

    private void GlideBy(double distance)
    {
        var max = Math.Max(0, _extent.Height - _viewport.Height);
        _glideTarget = Math.Clamp((_gliding ? _glideTarget : _offset.Y) + distance, 0, max);
        if (!SystemParameters.ClientAreaAnimation)
        {
            // Animations are off in Windows: jump as before.
            SetVerticalOffset(_glideTarget);
            return;
        }

        if (!_gliding)
        {
            _gliding = true;
            _lastFrame = TimeSpan.Zero;
            CompositionTarget.Rendering += OnGlideFrame;
        }
    }

    private void OnGlideFrame(object? sender, EventArgs e)
    {
        var now = ((RenderingEventArgs)e).RenderingTime;
        if (_lastFrame == TimeSpan.Zero)
        {
            _lastFrame = now;
            return;
        }

        // WPF can raise Rendering more than once per frame.
        var elapsed = (now - _lastFrame).TotalSeconds;
        if (elapsed <= 0)
        {
            return;
        }

        _lastFrame = now;
        var target = Math.Clamp(_glideTarget, 0, Math.Max(0, _extent.Height - _viewport.Height));
        var remaining = target - _offset.Y;
        if (Math.Abs(remaining) < 0.5 || !IsVisible)
        {
            StopGlide();
            MoveTo(target);
            return;
        }

        MoveTo(_offset.Y + remaining * (1 - Math.Exp(-elapsed / GlideSeconds)));
    }

    private void StopGlide()
    {
        if (_gliding)
        {
            _gliding = false;
            CompositionTarget.Rendering -= OnGlideFrame;
        }
    }

    public Rect MakeVisible(Visual visual, Rect rectangle)
    {
        for (var childIndex = 0; childIndex < InternalChildren.Count; childIndex++)
        {
            if (!ReferenceEquals(InternalChildren[childIndex], visual) && !InternalChildren[childIndex].IsAncestorOf(visual))
            {
                continue;
            }

            var itemIndex = ItemContainerGenerator.IndexFromGeneratorPosition(new GeneratorPosition(childIndex, 0));
            BringIndexIntoView(itemIndex);
            break;
        }

        return rectangle;
    }
}
