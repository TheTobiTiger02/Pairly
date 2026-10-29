using System.Collections.Specialized;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using Point = System.Windows.Point;
using Size = System.Windows.Size;

namespace SmartphoneLink.Windows.Controls;

/// <summary>
/// A virtualizing grid of square tiles. Columns are as many <see cref="ItemSize"/> tiles as fit, then
/// stretched to fill the width, so the grid always reaches both edges. Only the rows in view (plus
/// one either side) have containers, which keeps a photo library of thousands of thumbnails cheap.
/// Use with VirtualizingPanel.VirtualizationMode="Standard".
/// </summary>
public sealed class VirtualizingWrapPanel : VirtualizingPanel, IScrollInfo
{
    public static readonly DependencyProperty ItemSizeProperty = DependencyProperty.Register(
        nameof(ItemSize), typeof(double), typeof(VirtualizingWrapPanel),
        new FrameworkPropertyMetadata(176.0, FrameworkPropertyMetadataOptions.AffectsMeasure));

    private const double LineStep = 48;

    private Size _extent;
    private Size _viewport;
    private Point _offset;
    private int _columns = 1;
    private double _tileSize = 176;

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
        var rows = (itemCount + _columns - 1) / _columns;
        UpdateScrollInfo(new Size(width, rows * _tileSize), new Size(width, height));

        // Touching InternalChildren first makes sure the generator is ready.
        var children = InternalChildren;
        var generator = ItemContainerGenerator;
        if (itemCount == 0 || generator is null)
        {
            CleanUpItems(0, -1);
            return new Size(width, height);
        }

        var firstRow = Math.Max(0, (int)(_offset.Y / _tileSize) - 1);
        var lastRow = (int)Math.Ceiling((_offset.Y + height) / _tileSize);
        var firstIndex = Math.Min(itemCount - 1, firstRow * _columns);
        var lastIndex = Math.Min(itemCount - 1, (lastRow + 1) * _columns - 1);

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

                child.Measure(new Size(_tileSize, _tileSize));
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
            var row = itemIndex / _columns;
            var column = itemIndex % _columns;
            InternalChildren[childIndex].Arrange(new Rect(
                column * _tileSize, row * _tileSize - _offset.Y, _tileSize, _tileSize));
        }

        return finalSize;
    }

    protected override void OnItemsChanged(object sender, ItemsChangedEventArgs args)
    {
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
        var top = index / _columns * _tileSize;
        if (top < _offset.Y)
        {
            SetVerticalOffset(top);
        }
        else if (top + _tileSize > _offset.Y + _viewport.Height)
        {
            SetVerticalOffset(top + _tileSize - _viewport.Height);
        }
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
    public void MouseWheelUp() => SetVerticalOffset(_offset.Y - LineStep * SystemParameters.WheelScrollLines / 1.5);
    public void MouseWheelDown() => SetVerticalOffset(_offset.Y + LineStep * SystemParameters.WheelScrollLines / 1.5);
    public void LineLeft() { }
    public void LineRight() { }
    public void PageLeft() { }
    public void PageRight() { }
    public void MouseWheelLeft() { }
    public void MouseWheelRight() { }
    public void SetHorizontalOffset(double offset) { }

    public void SetVerticalOffset(double offset)
    {
        offset = Math.Clamp(offset, 0, Math.Max(0, _extent.Height - _viewport.Height));
        if (Math.Abs(offset - _offset.Y) < 0.1)
        {
            return;
        }

        _offset.Y = offset;
        ScrollOwner?.InvalidateScrollInfo();
        InvalidateMeasure();
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
