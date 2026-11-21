using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace AirBridge
{
    public partial class ClipboardFiltersWindow : Window
    {
        public ClipboardFiltersWindow()
        {
            InitializeComponent();
            UiKit.Dark(this);
            UiKit.Glass(this);
            RefreshList();
        }

        private void Window_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left) this.DragMove();
        }

        private void RefreshList()
        {
            FiltersList.ItemsSource = null;
            FiltersList.ItemsSource = ClipboardManager.Filters;
        }

        private void Add_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrWhiteSpace(KeywordInput.Text)) return;

            ClipboardManager.Filters.Add(new ClipboardFilter
            {
                Type = (FilterType)TypeCombo.SelectedIndex,
                Keyword = KeywordInput.Text
            });

            ClipboardManager.SaveFilters();
            KeywordInput.Text = "";
            RefreshList();
        }

        private void Delete_Click(object sender, RoutedEventArgs e)
        {
            if ((sender as Button)?.DataContext is ClipboardFilter filter)
            {
                ClipboardManager.Filters.Remove(filter);
                ClipboardManager.SaveFilters();
                RefreshList();
            }
        }

        private void Close_Click(object sender, RoutedEventArgs e) => this.Close();
    }
}