using System.Text;

namespace Vaier.Core;

/// <summary>
/// The Vaier host appended to VaierSetup.exe as it is served:
/// <c>[host UTF-8][host length, uint32 little-endian]["VAIERHOST1"]</c>. The exe ignores trailing bytes, so
/// one build serves every Vaier. Nothing here trusts the bytes: anything else reads back as no stamp.
/// </summary>
public static class SetupStamp
{
    private static readonly byte[] Magic = Encoding.ASCII.GetBytes("VAIERHOST1");
    private const int MaxHost = 253;

    public static string? Read(Stream file)
    {
        var trailer = Magic.Length + sizeof(int);
        if (file.Length < trailer) return null;

        var tail = new byte[trailer];
        file.Seek(-trailer, SeekOrigin.End);
        file.ReadExactly(tail);
        if (!tail.AsSpan(sizeof(int)).SequenceEqual(Magic)) return null;

        var length = BitConverter.ToInt32(tail, 0);
        if (length is <= 0 or > MaxHost || length > file.Length - trailer) return null;

        var host = new byte[length];
        file.Seek(-(trailer + length), SeekOrigin.End);
        file.ReadExactly(host);
        return VaierAddress.Normalise(Encoding.UTF8.GetString(host));
    }
}
