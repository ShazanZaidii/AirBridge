using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace AirBridge
{
    public partial class DisplayArrangementWindow : Window
    {
        private bool _isDragging = false;
        private Point _clickOffset;

        // UI Representation dimensions matching the XAML Canvas
        private const double MacWidth = 400;
        private const double MacHeight = 250;
        private const double PhoneWidth = 70;
        private const double PhoneHeight = 140;
        private const double MacLeft = 100;
        private const double MacTop = 55;

        public DisplayArrangementWindow()
        {
            InitializeComponent();
            Loaded += OnLoaded;
        }

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            // Only drag the window if we aren't dragging the phone widget
            if (e.ChangedButton == MouseButton.Left && !_isDragging) this.DragMove();
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            // Initialize the phone widget's position based on the current Interceptor settings
            double startX = MacLeft;
            double startY = MacTop;

            switch (InputInterceptor.PhoneEdge)
            {
                case DeviceEdge.Right:
                    startX = MacLeft + MacWidth;
                    startY = MacTop + (InputInterceptor.PhoneOffset * MacHeight);
                    break;
                case DeviceEdge.Left:
                    startX = MacLeft - PhoneWidth;
                    startY = MacTop + (InputInterceptor.PhoneOffset * MacHeight);
                    break;
                case DeviceEdge.Bottom:
                    startX = MacLeft + (InputInterceptor.PhoneOffset * MacWidth);
                    startY = MacTop + MacHeight;
                    break;
                case DeviceEdge.Top:
                    startX = MacLeft + (InputInterceptor.PhoneOffset * MacWidth);
                    startY = MacTop - PhoneHeight;
                    break;
            }

            Canvas.SetLeft(PhoneScreen, startX);
            Canvas.SetTop(PhoneScreen, startY);
        }

        private void Phone_MouseDown(object sender, MouseButtonEventArgs e)
        {
            _isDragging = true;
            _clickOffset = e.GetPosition(PhoneScreen);
            PhoneScreen.CaptureMouse();
        }

        private void Phone_MouseMove(object sender, MouseEventArgs e)
        {
            if (_isDragging)
            {
                Point canvasPos = e.GetPosition(LayoutCanvas);
                double rawX = canvasPos.X - _clickOffset.X;
                double rawY = canvasPos.Y - _clickOffset.Y;

                Point snapped = CalculateSnap(rawX, rawY);

                Canvas.SetLeft(PhoneScreen, snapped.X);
                Canvas.SetTop(PhoneScreen, snapped.Y);
            }
        }

        private void Phone_MouseUp(object sender, MouseButtonEventArgs e)
        {
            if (_isDragging)
            {
                _isDragging = false;
                PhoneScreen.ReleaseMouseCapture();
                ApplySettingsToInterceptor();
            }
        }

        // Mirrors the exact snapping logic from your Mac SwiftUI code
        private Point CalculateSnap(double x, double y)
        {
            double distRight = Math.Abs(x - (MacLeft + MacWidth));
            double distLeft = Math.Abs(x - (MacLeft - PhoneWidth));
            double distBottom = Math.Abs(y - (MacTop + MacHeight));
            double distTop = Math.Abs(y - (MacTop - PhoneHeight));

            double minDist = Math.Min(Math.Min(distRight, distLeft), Math.Min(distBottom, distTop));

            double newX = x;
            double newY = y;
            double padding = 10; // Slide tolerance before clamping

            if (minDist == distRight)
            {
                newX = MacLeft + MacWidth;
                newY = Math.Max(MacTop - padding, Math.Min(y, MacTop + MacHeight - PhoneHeight + padding));
            }
            else if (minDist == distLeft)
            {
                newX = MacLeft - PhoneWidth;
                newY = Math.Max(MacTop - padding, Math.Min(y, MacTop + MacHeight - PhoneHeight + padding));
            }
            else if (minDist == distBottom)
            {
                newY = MacTop + MacHeight;
                newX = Math.Max(MacLeft - padding, Math.Min(x, MacLeft + MacWidth - PhoneWidth + padding));
            }
            else
            {
                newY = MacTop - PhoneHeight;
                newX = Math.Max(MacLeft - padding, Math.Min(x, MacLeft + MacWidth - PhoneWidth + padding));
            }

            return new Point(newX, newY);
        }

        // Translates the physical Canvas coordinates back to routing math
        private void ApplySettingsToInterceptor()
        {
            double x = Canvas.GetLeft(PhoneScreen);
            double y = Canvas.GetTop(PhoneScreen);

            DeviceEdge edge;
            double offset = 0;
            double size = 0.5;

            // Added 0.5 tolerances for floating point imprecision
            if (x >= (MacLeft + MacWidth) - 0.5)
            {
                edge = DeviceEdge.Right;
                offset = (y - MacTop) / MacHeight;
                size = PhoneHeight / MacHeight;
            }
            else if (x <= (MacLeft - PhoneWidth) + 0.5)
            {
                edge = DeviceEdge.Left;
                offset = (y - MacTop) / MacHeight;
                size = PhoneHeight / MacHeight;
            }
            else if (y >= (MacTop + MacHeight) - 0.5)
            {
                edge = DeviceEdge.Bottom;
                offset = (x - MacLeft) / MacWidth;
                size = PhoneWidth / MacWidth;
            }
            else
            {
                edge = DeviceEdge.Top;
                offset = (x - MacLeft) / MacWidth;
                size = PhoneWidth / MacWidth;
            }

            // Clamp values safely (0.0 to 1.0) and push live to the interceptor
            InputInterceptor.PhoneEdge = edge;
            InputInterceptor.PhoneOffset = Math.Max(0, Math.Min(1, offset));
            InputInterceptor.PhoneSize = Math.Max(0.1, Math.Min(1, size));
        }

        private void Close_Click(object sender, RoutedEventArgs e) => this.Close();
    }
}