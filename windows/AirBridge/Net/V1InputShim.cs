using System.Threading.Tasks;

namespace AirBridge
{
    // TEMPORARY: the pre-v2 InputInterceptor.cs (owned by the input half) still references App.Server.
    // This inert stub keeps it compiling on the network branch. Delete this file once the v2 input half is merged.
    public partial class App
    {
        internal static V1ServerShim? Server => null;
    }

    internal sealed class V1ServerShim
    {
        public bool HasActiveClient => false;
        public Task SendAsync(string command) => Task.CompletedTask;
    }
}
