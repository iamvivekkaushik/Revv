# CarPlay documentation

Revv's CarPlay stack (`carplay/`) is a fork of [DiPlay](https://github.com/shihabal3amri/DiPlay), itself based on
[xcertplay](https://github.com/shilapi/xcertplay). The documents in this folder are DiPlay's and the
RevvCarPlay companion's, carried over for reference and attribution: compatibility, connection setup, BYD
navigation outputs, validation and testing notes, the upstream READMEs, the third-party notices and the licenses.

They predate the merge into Revv. Only the CarPlay engine came across (`carplay/common/src/main/java/com/shilapi/xcertplay/embed/`
and what it needs); DiPlay's own screens, its full-screen CarPlay window, the dashboard-map card, the cluster map
and the launcher widget did not. Where these documents say to open DiPlay or the RevvCarPlay companion, use Revv's
**Settings › CarPlay**, and where they build `:mobile`, build `:app`. The host protocol they describe
(`CarPlayEmbedService`) no longer exists: Revv calls the engine directly (`CarPlayHost.kt`).
