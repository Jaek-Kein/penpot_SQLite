# Licensing of the local desktop fork

Reviewed on November 24, 2026. This review covers the repository license, the local additions and the main components the setup script downloads. The prototype downloads the upstream binaries directly from their publishers. An installer that bundles those binaries requires a review of its actual contents.

## Using and sharing the fork

| Use | What applies |
| --- | --- |
| Personal development and use on your PC | You can run and modify the MPL-covered code. Private modifications do not have to be published. |
| Publishing modified source on GitHub | Keep the upstream copyright and license notices, retain the MPL text, and make MPL-covered source and modifications available under MPL 2.0. New files containing upstream MPL code are covered. Independently written files containing no MPL code may use a separate license. This prototype marks its own implementation files MPL-2.0. |
| Giving someone a Windows build, free or paid | Make the corresponding MPL-covered source available to recipients and tell them how to get it by a reasonable means. A release tag or source archive with the exact revisions and changes, linked from the installer or release notes, is a practical way to do that. Preserve notices. Separate components can retain their own licenses. |
| Naming a public fork | MPL grants rights to use the software. Its trademark exclusion covers the upstream project's trademarks and logos, except as needed for required notices. Use a distinct public product identity and identify Penpot as the upstream project. |

The governing sections are 1.10, 2.1, 2.3 and 3.1–3.4. Mozilla explains internal use, distribution, file-level copyleft and executable/source availability in its FAQ. [Repository license](../LICENSE) · [MPL 2.0](https://www.mozilla.org/en-US/MPL/2.0/) · [Mozilla FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/) · [Penpot terms, section 4](https://penpot.app/terms)

## Components in this build

- **Penpot frontend and shared model** — The setup script pins artifacts from the official 2.19.5 release. The local service uses the shared Clojure document rules from the release JAR. Keep the Penpot notice from the frontend bundle and link the matching upstream source alongside the local changes if distributing a build. [Upstream 2.19.5 source](https://github.com/penpot/penpot/tree/2.19.5)
- **SQLite and its Java driver** — SQLite dedicates its core to the public domain. The release JAR embeds Xerial SQLite JDBC 3.53.4.0, including its Windows native library. Xerial supplies an Apache 2.0 license and a separate Zentus notice. Both are present under `META-INF/maven/org.xerial/sqlite-jdbc/` in the JAR. Preserve the applicable texts and notices when repackaging. [SQLite copyright](https://sqlite.org/copyright.html) · [Xerial license](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/LICENSE) · [Zentus notice](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/LICENSE.zentus)
- **Java runtime** — Azul Zulu 27.30 (27.0.1.1+4) contains OpenJDK components under GPLv2 with the Classpath Exception and other third-party code. Its `legal/` directory, `DISCLAIMER` and distribution-specific terms belong with a redistributed runtime. Bundling it also requires satisfying the source obligations for the covered runtime components. [Azul distribution licensing](https://docs.azul.com/core/tpls/november-2026/zulu27_jdk_tpl.html)
- **Frontend dependencies, fonts and artwork** — Check the notices for the exact files shipped. For example the fork's Mousetrap package declares `Apache-2.0 WITH LLVM-exception`, and Source Sans has its own font license. The editor, the JAR's transitive dependencies and their assets require an inventory before distributing a single bundled installer. [Mousetrap license](../frontend/packages/mousetrap/LICENSE) · [Source Sans license](https://github.com/adobe-fonts/source-sans/blob/release/LICENSE.md)
- **Browser** — The launcher uses Edge or Chrome already installed on Windows. This repository's setup script does not redistribute the browser.
- **Start menu icon** — `penpot.ico` is a Windows icon made from the upstream [editor favicon](../frontend/resources/images/favicon.png). The trademark considerations above also apply to this asset.

Penpot distinguishes its open-source modules from separately licensed proprietary modules. This review covers the public fork and the local setup described above. An enterprise add-on acquired separately would carry its own terms. [Penpot terms, sections 4.1–4.2](https://penpot.app/terms)
