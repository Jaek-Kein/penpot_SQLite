// SPDX-License-Identifier: MPL-2.0
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Windows.Forms;

[assembly: AssemblyTitle("Penpot SQLite")]
[assembly: AssemblyProduct("Penpot SQLite")]

internal static class Launcher
{
    [STAThread]
    private static int Main()
    {
        try
        {
            string root = AppDomain.CurrentDomain.BaseDirectory;
            string script = Path.Combine(root, "desktop", "Start.ps1");
            if (!File.Exists(script))
                throw new FileNotFoundException("Keep the launcher in the Penpot SQLite folder.", script);

            var host = new ProcessStartInfo
            {
                FileName = Path.Combine(Environment.SystemDirectory, @"WindowsPowerShell\v1.0\powershell.exe"),
                Arguments = "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy RemoteSigned -File \"" + script + "\" -FromDesktop",
                WorkingDirectory = root,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            // The script owns the editor lifetime. Releasing this process handle
            // lets the GUI launcher leave memory as soon as the host starts.
            using (Process process = Process.Start(host)) { }
            return 0;
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "Penpot SQLite", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}
