using System.ComponentModel;
using System.Globalization;
using System.Resources;
using System.Windows.Data;
using System.Windows.Markup;
using Binding = System.Windows.Data.Binding;

namespace Pairly.Windows.Localization;

/// <summary>
/// The app's text in the chosen language. XAML binds through <see cref="LocExtension"/>, so
/// switching the language updates every bound text at once; code uses <see cref="L"/>.
/// English lives in Strings.resx, German in Strings.de.resx (same keys).
/// </summary>
public sealed class LocalizationSource : INotifyPropertyChanged
{
    private static readonly ResourceManager Strings = new("Pairly.Windows.Localization.Strings", typeof(LocalizationSource).Assembly);
    private static readonly CultureInfo SystemCulture = CultureInfo.CurrentUICulture;

    public static LocalizationSource Instance { get; } = new();

    public event PropertyChangedEventHandler? PropertyChanged;

    public event EventHandler? LanguageChanged;

    public CultureInfo Culture { get; private set; } = SystemCulture;

    /// <summary>"" follows Windows; otherwise "en" or "de".</summary>
    public string Language { get; private set; } = string.Empty;

    public string this[string key] => Strings.GetString(key, Culture) ?? key;

    public void SetLanguage(string? language)
    {
        Language = language is "en" or "de" ? language : string.Empty;
        Culture = Language.Length == 0 ? SystemCulture : CultureInfo.GetCultureInfo(Language);
        CultureInfo.CurrentUICulture = Culture;
        CultureInfo.DefaultThreadCurrentUICulture = Culture;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs("Item[]"));
        LanguageChanged?.Invoke(this, EventArgs.Empty);
    }
}

/// <summary>Text lookups for code: <c>L.T("key")</c> and <c>L.F("key", args)</c> for "{0}" placeholders.</summary>
public static class L
{
    public static string T(string key) => LocalizationSource.Instance[key];

    public static string F(string key, params object?[] args) =>
        string.Format(LocalizationSource.Instance.Culture, LocalizationSource.Instance[key], args);
}

/// <summary><c>Text="{l:Loc settings_title}"</c>: a live binding to the localized text.</summary>
[MarkupExtensionReturnType(typeof(object))]
public sealed class LocExtension : MarkupExtension
{
    public LocExtension() { }

    public LocExtension(string key) => Key = key;

    [ConstructorArgument("key")]
    public string Key { get; set; } = string.Empty;

    public override object ProvideValue(IServiceProvider serviceProvider) =>
        new Binding($"[{Key}]") { Source = LocalizationSource.Instance, Mode = BindingMode.OneWay }.ProvideValue(serviceProvider);
}
