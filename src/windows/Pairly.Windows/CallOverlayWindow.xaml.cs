using System.Net.Http;
using System.Net.Http.Json;
using System.Windows;
using Pairly.Protocol;

namespace Pairly.Windows;

/// <summary>
/// The call card in the corner of the screen. Ringing offers Answer / Decline / Silence; once the
/// phone reports the call in progress it switches to Hang up. The card closes when the phone
/// reports the call over, not when a button is pressed, so it always reflects the real call state.
/// </summary>
public partial class CallOverlayWindow : Window
{
    private readonly string _deviceId;
    private readonly HttpClient _client;
    private string _sessionId;

    public CallOverlayWindow(string deviceId, string sessionId, string callerName, string number, HttpClient client)
    {
        InitializeComponent();
        _deviceId = deviceId;
        _sessionId = sessionId;
        _client = client;
        ShowCaller(callerName, number);

        var workingArea = SystemParameters.WorkArea;
        Left = workingArea.Right - Width - 8;
        Top = workingArea.Bottom - Height - 4;
    }

    public string DeviceId => _deviceId;

    /// <summary>Switches the card to the in-progress state.</summary>
    public void ShowActive(string sessionId, string callerName, string number)
    {
        _sessionId = sessionId;
        if (!string.IsNullOrWhiteSpace(callerName) || !string.IsNullOrWhiteSpace(number))
        {
            ShowCaller(callerName, number);
        }

        StateText.Text = "Call in progress";
        HintText.Visibility = Visibility.Visible;
        SilenceButton.Visibility = Visibility.Collapsed;
        DeclineButton.Visibility = Visibility.Collapsed;
        AnswerButton.Visibility = Visibility.Collapsed;
        HangUpButton.Visibility = Visibility.Visible;
        HangUpButton.IsEnabled = true;
    }

    private void ShowCaller(string callerName, string number)
    {
        CallerNameText.Text = string.IsNullOrWhiteSpace(callerName)
            ? string.IsNullOrWhiteSpace(number) ? "Unknown caller" : number
            : callerName;
        CallerNumberText.Text = string.IsNullOrWhiteSpace(callerName) ? string.Empty : number;
        CallerNumberText.Visibility = string.IsNullOrWhiteSpace(CallerNumberText.Text) ? Visibility.Collapsed : Visibility.Visible;
    }

    private async void Action_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.Button { Tag: string action } button)
        {
            return;
        }

        button.IsEnabled = false;
        try
        {
            using var response = await _client.PostAsJsonAsync(
                $"v1/devices/{Uri.EscapeDataString(_deviceId)}/call/action",
                new CallActionPayload(_sessionId, action));
            if (!response.IsSuccessStatusCode)
            {
                StateText.Text = "Phone not connected";
                button.IsEnabled = true;
            }
        }
        catch (HttpRequestException)
        {
            StateText.Text = "Phone not connected";
            button.IsEnabled = true;
        }

        if (action == "silence")
        {
            StateText.Text = "Incoming call (silenced)";
        }
    }
}
