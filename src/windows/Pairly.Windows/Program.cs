using Velopack;

namespace Pairly.Windows;

public static class Program
{
    [STAThread]
    public static void Main(string[] args)
    {
        // Must run first: handles Velopack install/uninstall/update hooks (which exit early) and
        // applies an update downloaded during the previous run. A no-op for the Store package
        // and development builds.
        VelopackApp.Build().Run();

        var app = new App();
        app.InitializeComponent();
        app.Run();
    }
}
