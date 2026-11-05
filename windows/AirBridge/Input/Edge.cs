namespace AirBridge.Input
{
    public enum Edge { Left, Right, Top, Bottom }

    public static class EdgeExt
    {
        public static string ToWire(this Edge e) => e switch
        {
            Edge.Left => "left",
            Edge.Right => "right",
            Edge.Top => "top",
            _ => "bottom",
        };

        public static Edge? Parse(string? s) => s?.ToLowerInvariant() switch
        {
            "left" => Edge.Left,
            "right" => Edge.Right,
            "top" => Edge.Top,
            "bottom" => Edge.Bottom,
            _ => null,
        };

        public static Edge Opposite(this Edge e) => e switch
        {
            Edge.Left => Edge.Right,
            Edge.Right => Edge.Left,
            Edge.Top => Edge.Bottom,
            _ => Edge.Top,
        };

        public static bool IsVertical(this Edge e) => e == Edge.Left || e == Edge.Right;
    }
}
