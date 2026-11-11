using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace Pairly.Windows.Controls;

/// <summary>
/// The link between this PC and the phone: a short track with a piece of the accent travelling
/// along it while the phone is connected. It stands still and empty when the phone is offline.
/// </summary>
public sealed class LinkLine : Border
{
    public static readonly DependencyProperty IsActiveProperty = DependencyProperty.Register(
        nameof(IsActive), typeof(bool), typeof(LinkLine), new PropertyMetadata(false, (d, _) => ((LinkLine)d).Update()));

    private readonly Border _runner;
    private readonly TranslateTransform _offset = new();

    public LinkLine()
    {
        Width = 26;
        Height = 2;
        CornerRadius = new CornerRadius(1);
        ClipToBounds = true;
        VerticalAlignment = VerticalAlignment.Center;
        SnapsToDevicePixels = true;
        SetResourceReference(BackgroundProperty, "CardStrokeBrush");
        _runner = new Border
        {
            Width = 10,
            CornerRadius = new CornerRadius(1),
            HorizontalAlignment = System.Windows.HorizontalAlignment.Left,
            RenderTransform = _offset
        };
        _runner.SetResourceReference(BackgroundProperty, "AccentBrush");
        Child = _runner;
        Loaded += (_, _) => Update();
        Unloaded += (_, _) => _offset.BeginAnimation(TranslateTransform.XProperty, null);
    }

    public bool IsActive
    {
        get => (bool)GetValue(IsActiveProperty);
        set => SetValue(IsActiveProperty, value);
    }

    private void Update()
    {
        _runner.Visibility = IsActive ? Visibility.Visible : Visibility.Hidden;
        if (!IsActive || !IsLoaded || !Motion.Enabled)
        {
            // Without motion the piece rests in the middle of the track.
            _offset.BeginAnimation(TranslateTransform.XProperty, null);
            _offset.X = (Width - _runner.Width) / 2;
            return;
        }

        var travel = new DoubleAnimationUsingKeyFrames { Duration = TimeSpan.FromSeconds(1.8), RepeatBehavior = RepeatBehavior.Forever };
        travel.KeyFrames.Add(new DiscreteDoubleKeyFrame(-_runner.Width, KeyTime.FromPercent(0)));
        travel.KeyFrames.Add(new EasingDoubleKeyFrame(Width, KeyTime.FromPercent(0.6), new CubicEase { EasingMode = EasingMode.EaseInOut }));
        _offset.BeginAnimation(TranslateTransform.XProperty, travel);
    }
}
