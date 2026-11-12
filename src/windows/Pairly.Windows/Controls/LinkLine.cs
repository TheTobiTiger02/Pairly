using System.Windows;
using System.Windows.Controls;

namespace Pairly.Windows.Controls;

/// <summary>
/// The link between this PC and the phone: a short line in the accent while the phone is
/// connected, in the neutral edge color while it is not.
/// </summary>
public sealed class LinkLine : Border
{
    public static readonly DependencyProperty IsActiveProperty = DependencyProperty.Register(
        nameof(IsActive), typeof(bool), typeof(LinkLine), new PropertyMetadata(false, (d, _) => ((LinkLine)d).Update()));

    public LinkLine()
    {
        Width = 20;
        Height = 2;
        CornerRadius = new CornerRadius(1);
        VerticalAlignment = VerticalAlignment.Center;
        SnapsToDevicePixels = true;
        Update();
    }

    public bool IsActive
    {
        get => (bool)GetValue(IsActiveProperty);
        set => SetValue(IsActiveProperty, value);
    }

    private void Update() => SetResourceReference(BackgroundProperty, IsActive ? "AccentBrush" : "CardStrokeBrush");
}
