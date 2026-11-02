using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using Button = System.Windows.Controls.Button;
using KeyEventArgs = System.Windows.Input.KeyEventArgs;
using Rectangle = System.Windows.Shapes.Rectangle;
using Size = System.Windows.Size;
using Color = System.Windows.Media.Color;
using HorizontalAlignment = System.Windows.HorizontalAlignment;

namespace Pairly.Windows.Controls;

/// <summary>
/// A guided tour: dims the window, cuts a spotlight around one element at a time and explains it
/// in a callout next to it. Built in code so it can point at any element of either layout.
/// </summary>
public sealed class CoachMarkOverlay : Grid
{
    public sealed record Step(FrameworkElement Target, string Title, string Body);

    private const double CalloutWidth = 320;
    private const double Gap = 16;

    private readonly IReadOnlyList<Step> _steps;
    private readonly string _nextText;
    private readonly string _doneText;
    private readonly string _counterFormat;
    private readonly RectangleGeometry _hole = new(Rect.Empty, 10, 10);
    private readonly RectangleGeometry _all = new();
    private readonly Rectangle _ring = new() { StrokeThickness = 2, RadiusX = 10, RadiusY = 10, IsHitTestVisible = false };
    private readonly Canvas _canvas = new();
    private readonly Border _callout;
    private readonly TextBlock _title = new() { FontSize = 16, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _body = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 6, 0, 0) };
    private readonly TextBlock _counter = new() { VerticalAlignment = VerticalAlignment.Center };
    private readonly Button _back;
    private readonly Button _next;
    private int _index = -1;

    public event EventHandler? Finished;

    public CoachMarkOverlay(IReadOnlyList<Step> steps, string backText, string nextText, string doneText, string skipText, string counterFormat)
    {
        _steps = steps.Where(step => step.Target.IsVisible && step.Target.ActualWidth > 0).ToList();
        _nextText = nextText;
        _doneText = doneText;
        _counterFormat = counterFormat;
        Focusable = true;

        var dim = new Path
        {
            Fill = new SolidColorBrush(Color.FromArgb(0x99, 0, 0, 0)),
            Data = new CombinedGeometry(GeometryCombineMode.Exclude, _all, _hole)
        };
        _ring.SetResourceReference(Shape.StrokeProperty, "AccentBrush");
        _counter.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
        _counter.SetResourceReference(StyleProperty, "CaptionText");
        _body.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");

        _back = new Button { Content = backText, Margin = new Thickness(0, 0, 8, 0), MinWidth = 72 };
        _back.SetResourceReference(StyleProperty, "SubtleButton");
        _back.Click += (_, _) => Show(_index - 1);
        _next = new Button { MinWidth = 96 };
        _next.SetResourceReference(StyleProperty, "AccentButton");
        _next.Click += (_, _) => Show(_index + 1);
        var skip = new Button { Content = skipText, HorizontalAlignment = HorizontalAlignment.Left };
        skip.SetResourceReference(StyleProperty, "TextLinkButton");
        skip.Click += (_, _) => Close();

        var buttons = new DockPanel { Margin = new Thickness(0, 18, 0, 0), LastChildFill = false };
        DockPanel.SetDock(_next, Dock.Right);
        DockPanel.SetDock(_back, Dock.Right);
        buttons.Children.Add(_next);
        buttons.Children.Add(_back);
        buttons.Children.Add(_counter);

        var content = new StackPanel();
        content.Children.Add(_title);
        content.Children.Add(_body);
        content.Children.Add(buttons);
        content.Children.Add(skip);

        _callout = new Border
        {
            Width = CalloutWidth,
            Padding = new Thickness(20, 18, 20, 12),
            CornerRadius = new CornerRadius(10),
            BorderThickness = new Thickness(1),
            Child = content,
            RenderTransform = new TranslateTransform()
        };
        _callout.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
        _callout.SetResourceReference(Border.BorderBrushProperty, "FlyoutStrokeBrush");
        _callout.SetResourceReference(EffectProperty, "DialogShadow");

        _canvas.Children.Add(_ring);
        _canvas.Children.Add(_callout);
        Children.Add(dim);
        Children.Add(_canvas);

        Loaded += (_, _) => { Focus(); Show(0); };
        SizeChanged += (_, _) => Position(animate: false);
        KeyDown += OnKeyDown;
    }

    public void Close()
    {
        Visibility = Visibility.Collapsed;
        Finished?.Invoke(this, EventArgs.Empty);
    }

    private void OnKeyDown(object sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.Escape: Close(); e.Handled = true; break;
            case Key.Right or Key.Enter: Show(_index + 1); e.Handled = true; break;
            case Key.Left: Show(_index - 1); e.Handled = true; break;
        }
    }

    private void Show(int index)
    {
        if (index >= _steps.Count || _steps.Count == 0)
        {
            Close();
            return;
        }
        if (index < 0)
        {
            return;
        }

        _index = index;
        var step = _steps[index];
        _title.Text = step.Title;
        _body.Text = step.Body;
        _counter.Text = string.Format(_counterFormat, index + 1, _steps.Count);
        _back.Visibility = index > 0 ? Visibility.Visible : Visibility.Collapsed;
        _next.Content = index == _steps.Count - 1 ? _doneText : _nextText;
        _next.Focus();
        Position(animate: true);
    }

    private void Position(bool animate)
    {
        if (_index < 0 || ActualWidth <= 0)
        {
            return;
        }

        _all.Rect = new Rect(0, 0, ActualWidth, ActualHeight);
        var target = _steps[_index].Target;
        Rect bounds;
        try
        {
            bounds = target.TransformToVisual(this).TransformBounds(new Rect(target.RenderSize));
        }
        catch (InvalidOperationException)
        {
            return; // The target left the visual tree (layout switched).
        }
        bounds.Inflate(6, 6);

        var animateNow = animate && Motion.Enabled && !_hole.Rect.IsEmpty;
        var duration = TimeSpan.FromMilliseconds(320);
        var ease = new CubicEase { EasingMode = EasingMode.EaseInOut };
        if (animateNow)
        {
            _hole.BeginAnimation(RectangleGeometry.RectProperty, new RectAnimation(_hole.Rect, bounds, duration) { EasingFunction = ease });
        }
        else
        {
            _hole.BeginAnimation(RectangleGeometry.RectProperty, null);
            _hole.Rect = bounds;
        }
        _ring.Width = bounds.Width;
        _ring.Height = bounds.Height;
        Canvas.SetLeft(_ring, bounds.Left);
        Canvas.SetTop(_ring, bounds.Top);

        // The callout goes right of the spotlight if it fits, else below, else left, else above.
        _callout.Measure(new Size(CalloutWidth, double.PositiveInfinity));
        var height = _callout.DesiredSize.Height;
        double x, y;
        if (bounds.Right + Gap + CalloutWidth <= ActualWidth - Gap)
        {
            x = bounds.Right + Gap;
            y = Math.Clamp(bounds.Top, Gap, Math.Max(Gap, ActualHeight - height - Gap));
        }
        else if (bounds.Bottom + Gap + height <= ActualHeight - Gap)
        {
            x = Math.Clamp(bounds.Left, Gap, Math.Max(Gap, ActualWidth - CalloutWidth - Gap));
            y = bounds.Bottom + Gap;
        }
        else if (bounds.Left - Gap - CalloutWidth >= Gap)
        {
            x = bounds.Left - Gap - CalloutWidth;
            y = Math.Clamp(bounds.Top, Gap, Math.Max(Gap, ActualHeight - height - Gap));
        }
        else
        {
            x = Math.Clamp(bounds.Left, Gap, Math.Max(Gap, ActualWidth - CalloutWidth - Gap));
            y = Math.Max(Gap, bounds.Top - Gap - height);
        }
        Canvas.SetLeft(_callout, x);
        Canvas.SetTop(_callout, y);

        if (animateNow || (animate && Motion.Enabled))
        {
            var translate = (TranslateTransform)_callout.RenderTransform;
            translate.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(10, 0, duration) { EasingFunction = ease });
            _callout.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(220)));
        }
    }
}
