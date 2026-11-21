using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace AirBridge
{
    /// <summary>
    /// AirBridge as an ordinary window (Start menu, double-click on the tray icon): the same content as the tray menu,
    /// in a resizable window. Built when asked for, released when closed.
    /// </summary>
    public sealed class MainWindow : Window
    {
        public MainWindow()
        {
            Title = "AirBridge";
            Width = 440;
            Height = 760;
            MinWidth = 380;
            MinHeight = 420;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            UseLayoutRounding = true;
            Background = (Brush)Application.Current.FindResource("AB.Background");
            UiKit.Dark(this);
            UiKit.Glass(this);
            var content = new TrayPopupControl { Embedded = true };
            content.RequestClose += () => { /* stays open: it is a window, not a menu */ };
            Content = new ScrollViewer { Style = (Style)Application.Current.FindResource("AB.ScrollViewer"), Content = content };
            Closed += (_, _) => { content.Detach(); UiKit.TrimMemory(force: true); };
        }
    }
}
