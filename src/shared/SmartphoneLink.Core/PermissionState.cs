namespace SmartphoneLink.Core;

public enum PermissionState
{
    Unknown,
    Granted,
    Denied,
    NeedsUserAction,
    NotAvailable
}

public static class PermissionStateMapper
{
    public static PermissionState FromAndroidGrant(bool available, bool granted, bool canRequestAgain)
    {
        if (!available)
        {
            return PermissionState.NotAvailable;
        }

        if (granted)
        {
            return PermissionState.Granted;
        }

        return canRequestAgain ? PermissionState.Denied : PermissionState.NeedsUserAction;
    }
}

