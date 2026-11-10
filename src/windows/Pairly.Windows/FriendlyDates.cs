using Pairly.Protocol;
using Pairly.Windows.Localization;

namespace Pairly.Windows;

/// <summary>Dates the way a person says them, in the app's language.</summary>
internal static class FriendlyDates
{
    /// <summary>"Today", "Yesterday", "Monday, 3 November", and with the year for other years.</summary>
    public static string Day(DateTime localDate)
    {
        var date = localDate.Date;
        if (date == DateTime.Today) return L.T("today");
        if (date == DateTime.Today.AddDays(-1)) return L.T("yesterday");
        var culture = LocalizationSource.Instance.Culture;
        return date.ToString(date.Year == DateTime.Today.Year ? "dddd, d MMMM" : "d MMMM yyyy", culture);
    }

    /// <summary>For a list: the time today, else a short date.</summary>
    public static string Short(DateTimeOffset moment)
    {
        var local = moment.LocalDateTime;
        var culture = LocalizationSource.Instance.Culture;
        if (local.Date == DateTime.Today) return local.ToString("t", culture);
        if (local.Date == DateTime.Today.AddDays(-1)) return L.T("yesterday");
        return local.ToString(local.Year == DateTime.Today.Year ? "d MMM" : "d MMM yyyy", culture);
    }
}

/// <summary>One message in a conversation, with the day heading above the first message of each day.</summary>
internal sealed class SmsMessageViewModel
{
    private SmsMessageViewModel(SmsMessagePayload payload, string? dayHeader)
    {
        Body = payload.Body;
        FromMe = payload.FromMe;
        SentAt = payload.SentAt;
        DayHeader = dayHeader;
    }

    public string Body { get; }
    public bool FromMe { get; }
    public DateTimeOffset SentAt { get; }
    public string? DayHeader { get; }
    public bool HasDayHeader => DayHeader is not null;
    public string TimeText => SentAt.LocalDateTime.ToString("t", LocalizationSource.Instance.Culture);

    public static IReadOnlyList<SmsMessageViewModel> From(IEnumerable<SmsMessagePayload> messages)
    {
        var result = new List<SmsMessageViewModel>();
        DateTime? previousDay = null;
        foreach (var message in messages)
        {
            var day = message.SentAt.LocalDateTime.Date;
            result.Add(new SmsMessageViewModel(message, day == previousDay ? null : FriendlyDates.Day(day)));
            previousDay = day;
        }

        return result;
    }
}
