# Local SQLite desktop prototype for Windows

Double-click `Penpot SQLite.cmd` in the repository root. The launcher opens the Penpot editor in a dedicated Edge or Chrome window. Windows x64 and an installed Edge or Chrome browser are required. The first setup downloads hash-pinned Penpot 2.19.5 assets and an Azul Java runtime. Subsequent launches use the installed files and can run offline.

After setup, press the Windows key, type **Penpot** and choose **Penpot SQLite**. If you move the repository, run `desktop/Register-StartMenu.ps1` again to update the shortcut.

The editor starts in the most recently modified file. The Penpot menu takes you back to the dashboard. Open Drafts to create another file. Close the editor windows to end the local server. The launcher owns the server's standard input so closing the launcher also releases the server. Wait for the editor to finish saving before closing it.

For a single shape, the editor can export PNG, SVG and PDF on this PC. Open the profile's Settings, choose Options and enable WebGL rendering. Reopen the document, select a shape, add an export in the right sidebar and download it. These exports run in the browser. If a download uses the previous format after a format change, wait for autosave and reopen the document. Batches and raster exports too large for the browser require Penpot's separate export service.

## Data and implementation

- `desktop/data/workspace.sqlite` stores documents, revisions, images and thumbnails. The browser profile and logs also live under `desktop/data`. To back up the workspace, close the app and copy the data directory.
- `desktop/.runtime` holds the runtime and download cache. `Setup.ps1` extracts the web bundle and JAR from the official release image layers without running containers. The exact upstream revision is [eaac3f5](https://github.com/penpot/penpot/tree/eaac3f58c42a56943c9e8e2ed4aedf580d4542dd).
- The local service binds to `127.0.0.1:17861`. It calls Penpot's shared file constructor, change processor and Transit reader/writer. SQLite commits the updated document and the retry receipt in one transaction. A stale save from another window reports a conflict.
- A new workspace contains a personal profile, a Drafts project and one blank document. This first version supports document creation, naming, drawing, autosave, reopening and local PNG/JPEG/GIF images. Collaboration, team administration, hosted plugins, native `.penpot` interchange and the export service are outside the implemented prototype. Some upstream menu items still appear and report an unsupported operation.
- The service uses a JVM during editing. Its Java heap limit is 448 MiB. Total working memory also includes native memory and the browser. No Docker engine, WSL distribution, PostgreSQL or Redis process is started.

## Verification

From the repository root, after setup, run the SQLite integration tests in PowerShell.

```powershell
$runtime = (Resolve-Path 'desktop/.runtime/2.19.5').Path
$java = Join-Path $runtime 'zulu27.30.201-ca-jre27.0.1.1-win_x64/bin/java.exe'
$jar = Join-Path $runtime 'opt/penpot/backend/penpot.jar'
& $java '--enable-native-access=ALL-UNNAMED' -cp "desktop/src;desktop/test;$jar" clojure.main -m local.store-test
& '.\desktop\test\start-menu.ps1'
```

For an isolated workspace, run `desktop/Start.ps1 -WorkspaceDirectory 'F:\Your test workspace' -Port 17863`.

## Licensing

The fork retains the upstream MPL 2.0 license. See the [distribution notes](LICENSING.md) for source, installer and third-party runtime obligations.
