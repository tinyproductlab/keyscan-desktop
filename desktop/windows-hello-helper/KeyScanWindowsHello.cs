using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using Windows.Foundation;
using Windows.Security.Credentials.UI;

namespace KeyScan.WindowsHello
{
    [ComImport]
    [Guid("39E050C3-4E74-441A-8DC0-B81104DF949C")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IUserConsentVerifierInterop
    {
        IAsyncOperation<UserConsentVerificationResult> RequestVerificationForWindowAsync(
            IntPtr appWindow,
            [MarshalAs(UnmanagedType.HString)] string message,
            [In] ref Guid riid);
    }

    internal static class Program
    {
        private const string PassportProvider = "Microsoft Passport Key Storage Provider";
        private const string RsaAlgorithm = "RSA";
        private const string UiPolicyProperty = "UI Policy";
        private const string WindowHandleProperty = "HWND Handle";
        private const int UiProtectKey = 0x1;
        private const int UiForceHighProtection = 0x2;
        private const int PadPkcs1 = 0x2;

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct NcryptUiPolicy
        {
            internal int Version;
            internal int Flags;
            internal IntPtr CreationTitle;
            internal IntPtr FriendlyName;
            internal IntPtr Description;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct BcryptPkcs1PaddingInfo { [MarshalAs(UnmanagedType.LPWStr)] internal string AlgorithmId; }

        [DllImport("ncrypt.dll", CharSet = CharSet.Unicode)]
        private static extern int NCryptOpenStorageProvider(out IntPtr provider, string providerName, int flags);
        [DllImport("ncrypt.dll", CharSet = CharSet.Unicode)]
        private static extern int NCryptCreatePersistedKey(IntPtr provider, out IntPtr key, string algorithm, string keyName, int legacyKeySpec, int flags);
        [DllImport("ncrypt.dll", CharSet = CharSet.Unicode)]
        private static extern int NCryptOpenKey(IntPtr provider, out IntPtr key, string keyName, int legacyKeySpec, int flags);
        [DllImport("ncrypt.dll", CharSet = CharSet.Unicode)]
        private static extern int NCryptSetProperty(IntPtr handle, string property, IntPtr input, int inputSize, int flags);
        [DllImport("ncrypt.dll")]
        private static extern int NCryptFinalizeKey(IntPtr key, int flags);
        [DllImport("ncrypt.dll", CharSet = CharSet.Unicode)]
        private static extern int NCryptSignHash(IntPtr key, ref BcryptPkcs1PaddingInfo paddingInfo, byte[] hash, int hashSize, byte[] signature, int signatureSize, out int resultSize, int flags);
        [DllImport("ncrypt.dll")]
        private static extern int NCryptDeleteKey(IntPtr key, int flags);
        [DllImport("ncrypt.dll")]
        private static extern int NCryptFreeObject(IntPtr handle);
        private static int Main(string[] args)
        {
            try { return Run(args).GetAwaiter().GetResult(); }
            catch (Exception ex)
            {
                Console.Out.WriteLine("ERROR:" + ex.GetType().Name + ":" + ex.HResult.ToString("X8"));
                return 2;
            }
        }

        private static async Task<int> Run(string[] args)
        {
            if (args.Length == 1 && args[0] == "check")
            {
                UserConsentVerifierAvailability availability = await UserConsentVerifier.CheckAvailabilityAsync().AsTask();
                Console.Out.WriteLine("AVAILABILITY:" + availability);
                return availability == UserConsentVerifierAvailability.Available ? 0 : 1;
            }
            if (args.Length == 3 && args[0] == "verify")
            {
                long rawHandle;
                if (!long.TryParse(args[1], out rawHandle) || rawHandle == 0) throw new ArgumentException("A non-zero HWND is required.");
                IUserConsentVerifierInterop interop = (IUserConsentVerifierInterop)
                    WindowsRuntimeMarshal.GetActivationFactory(typeof(UserConsentVerifier));
                Guid operationId = typeof(IAsyncOperation<UserConsentVerificationResult>).GUID;
                UserConsentVerificationResult result = await interop
                    .RequestVerificationForWindowAsync(new IntPtr(rawHandle), args[2], ref operationId).AsTask();
                Console.Out.WriteLine("VERIFICATION:" + result);
                return result == UserConsentVerificationResult.Verified ? 0 : 1;
            }
            if (args.Length == 2 && args[0] == "keycheck")
            {
                ValidateKeyName(args[1]);
                IntPtr provider = IntPtr.Zero, key = IntPtr.Zero;
                try
                {
                    Check(NCryptOpenStorageProvider(out provider, PassportProvider, 0), "open-provider");
                    int status = NCryptOpenKey(provider, out key, args[1], 0, 0);
                    Console.Out.WriteLine(status == 0 ? "KEY:Available" : "KEY:Missing");
                    return status == 0 ? 0 : 1;
                }
                finally { Free(key); Free(provider); }
            }
            if (args.Length == 3 && args[0] == "enroll")
            {
                long hwnd; if (!long.TryParse(args[1], out hwnd) || hwnd == 0) throw new ArgumentException("A non-zero HWND is required.");
                ValidateKeyName(args[2]);
                IntPtr provider = IntPtr.Zero, key = IntPtr.Zero;
                try
                {
                    Check(NCryptOpenStorageProvider(out provider, PassportProvider, 0), "open-provider");
                    Check(NCryptCreatePersistedKey(provider, out key, RsaAlgorithm, args[2], 0, 0), "create-key");
                    SetWindow(key, new IntPtr(hwnd)); SetStrongUi(key);
                    Check(NCryptFinalizeKey(key, 0), "finalize-key");
                    Console.Out.WriteLine("ENROLL:Created"); return 0;
                }
                catch { if (key != IntPtr.Zero) NCryptDeleteKey(key, 0); key = IntPtr.Zero; throw; }
                finally { Free(key); Free(provider); }
            }
            if (args.Length == 4 && args[0] == "sign")
            {
                long hwnd; if (!long.TryParse(args[1], out hwnd) || hwnd == 0) throw new ArgumentException("A non-zero HWND is required.");
                ValidateKeyName(args[2]);
                byte[] challenge = Convert.FromBase64String(args[3]);
                if (challenge.Length != 32) throw new ArgumentException("Challenge must be exactly 32 bytes.");
                IntPtr provider = IntPtr.Zero, key = IntPtr.Zero;
                try
                {
                    Check(NCryptOpenStorageProvider(out provider, PassportProvider, 0), "open-provider");
                    Check(NCryptOpenKey(provider, out key, args[2], 0, 0), "open-key"); SetWindow(key, new IntPtr(hwnd));
                    byte[] hash; using (SHA256 sha = SHA256.Create()) hash = sha.ComputeHash(challenge);
                    try { Console.Out.WriteLine("SIGNATURE:" + Convert.ToBase64String(Sign(key, hash))); return 0; }
                    finally { Array.Clear(hash, 0, hash.Length); }
                }
                finally { Array.Clear(challenge, 0, challenge.Length); Free(key); Free(provider); }
            }
            if (args.Length == 2 && args[0] == "delete-key")
            {
                ValidateKeyName(args[1]); IntPtr provider = IntPtr.Zero, key = IntPtr.Zero;
                try { Check(NCryptOpenStorageProvider(out provider, PassportProvider, 0), "open-provider"); Check(NCryptOpenKey(provider, out key, args[1], 0, 0), "open-key"); Check(NCryptDeleteKey(key, 0), "delete-key"); key = IntPtr.Zero; Console.Out.WriteLine("KEY:Deleted"); return 0; }
                finally { Free(key); Free(provider); }
            }
            Console.Error.WriteLine("Usage: KeyScanWindowsHello.exe check | verify <HWND> <message>");
            return 64;
        }

        private static void ValidateKeyName(string value) { if (!Regex.IsMatch(value ?? "", "^[A-Za-z0-9._-]{1,80}$")) throw new ArgumentException("Invalid key name."); }
        private static void Check(int status, string operation) { if (status != 0) throw new CryptographicException(operation + " failed: 0x" + status.ToString("X8")); }
        private static void Free(IntPtr handle) { if (handle != IntPtr.Zero) NCryptFreeObject(handle); }

        private static void SetWindow(IntPtr key, IntPtr hwnd)
        {
            IntPtr value = Marshal.AllocHGlobal(IntPtr.Size);
            try { Marshal.WriteIntPtr(value, hwnd); Check(NCryptSetProperty(key, WindowHandleProperty, value, IntPtr.Size, 0), "set-window"); }
            finally { Marshal.FreeHGlobal(value); }
        }

        private static void SetStrongUi(IntPtr key)
        {
            IntPtr friendly = Marshal.StringToHGlobalUni("KeyScan quick unlock"), description = Marshal.StringToHGlobalUni("Authenticate to unlock your local KeyScan vault"), memory = IntPtr.Zero;
            try
            {
                NcryptUiPolicy policy = new NcryptUiPolicy { Version = 1, Flags = UiProtectKey | UiForceHighProtection, FriendlyName = friendly, Description = description };
                memory = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NcryptUiPolicy))); Marshal.StructureToPtr(policy, memory, false);
                Check(NCryptSetProperty(key, UiPolicyProperty, memory, Marshal.SizeOf(typeof(NcryptUiPolicy)), 0), "set-ui-policy");
            }
            finally { if (memory != IntPtr.Zero) Marshal.FreeHGlobal(memory); Marshal.FreeHGlobal(friendly); Marshal.FreeHGlobal(description); }
        }

        private static byte[] Sign(IntPtr key, byte[] hash)
        {
            BcryptPkcs1PaddingInfo padding = new BcryptPkcs1PaddingInfo { AlgorithmId = "SHA256" }; int size;
            Check(NCryptSignHash(key, ref padding, hash, hash.Length, null, 0, out size, PadPkcs1), "sign-size");
            byte[] signature = new byte[size]; Check(NCryptSignHash(key, ref padding, hash, hash.Length, signature, signature.Length, out size, PadPkcs1), "sign");
            if (size != signature.Length) Array.Resize(ref signature, size); return signature;
        }
    }
}
