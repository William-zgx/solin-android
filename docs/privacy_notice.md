# Privacy Notice

This notice describes the privacy boundary implemented in this repository for
internal testing. It is not a final public legal policy. Review it with release,
security, and legal owners before broad distribution.

## Local Storage

PocketMind is local-first by default. The app stores chat sessions, chat
messages, model registry state, download records, scheduled background tasks,
explicit long-term memory records, Agent trace summaries, pending confirmation
snapshots, Skill checkpoints, and tool audit metadata in local Android app
storage. These records are not a cloud sync source in this codebase.

Local records may include user-entered chat text and assistant responses.
Privacy-sensitive generated or tool-derived content is marked `LocalOnly` where
the code can identify it. Examples include clipboard-derived messages,
shared-input excerpts, OCR excerpts, current-screen Accessibility text
continuations, local memory-control status turns, and local action turns.

Remote API keys are stored separately through Android Keystore-backed encrypted
preferences. Clearing the API key field removes the stored secret.

## Remote Model Mode

Remote model mode sends requests only to the user-configured OpenAI-compatible
chat endpoint. The request can include the current user prompt, selected model
name, generation parameters, and prior chat messages whose privacy is
`RemoteEligible`.

The app filters `LocalOnly` messages from remote history and rejects a
`LocalOnly` current prompt before making a remote request. Local memory hits,
device context, clipboard content, OCR text, current-screen Accessibility text,
shared-input excerpts, attachment metadata, and local action draft turns are
not automatically sent to a remote model. If the user manually types or pastes
the same content into a normal remote-eligible message, that message can be
sent.

Remote transport requires HTTPS, except for local debug hosts such as
`localhost`, `127.0.0.1`, and Android emulator `10.0.2.2`. When an API key is
configured, the runtime sends it as an authorization credential to the
configured endpoint. The endpoint operator's own logging and retention policies
apply.

## Device Context Tools

Device context tools are gated behind Agent planning, schema validation, safety
policy, and user confirmation. The current tool set includes bounded reads for
clipboard text, calendar busy/free windows, contact name/phone search, current
foreground app metadata, current-app notification metadata, recent file
metadata, recent screenshot/image OCR excerpts, and current-screen
Accessibility text snapshots.

These tools are designed to minimize returned data. For example, recent file
reads return metadata rather than file contents, paths, or URIs; current-screen
text reads use Accessibility text nodes rather than screenshots or pixels; OCR
tools avoid persisting image identifiers, paths, raw pixels, and raw OCR text in
trace or audit stores. Clipboard text, contact matches, calendar busy/free
windows, foreground-app metadata, current-app notification summaries, recent
file metadata, OCR excerpts, and current-screen Accessibility snapshots are
marked `LocalOnly` and `requiresLocalModel=true`; their local payload fields are
declared as private tool outputs so they remain inside local continuation,
observation, trace-redaction, and Skill public-output boundaries.

Android runtime permissions and special app access are requested only after the
user confirms the associated tool request. Permission denial is treated as a
structured tool failure rather than an automatic retry.

## External Intents And Sharing

Confirmed tools may open Android system screens, the share sheet, email drafts,
calendar drafts, contact drafts, web links, app launchers, or allowlisted app
settings pages. Once an external screen opens, the destination app or Android
system component may receive the prefilled data needed for that action. The
current app records this as an opened-but-unverified boundary; it cannot know
whether the user completed the destination app action.

System speech recognition inserts a transcript into the compose box only.
Sending remains explicit. Audio/video/legacy Office/binary attachments are
metadata-only in the current app; supported strict UTF-8 text, RTF, PDF text
layers, PDF scanned-page OCR fallback, Office Open XML, and user-provided image
attachments may produce bounded local excerpts. Malformed PDFs remain
metadata-only.

## Model Downloads

Recommended model downloads use Android `DownloadManager` and contact the
configured upstream download URLs. Recommended downloads are registered only
after SHA-256 verification against the pinned model manifest. Custom imported
models and custom URL downloads are user-supplied and are not covered by the
recommended-model provenance guarantees.

Model files are stored in local app storage and are not bundled into the APK.
Network operators and model hosts may receive normal download metadata such as
IP address, URL, user agent, timing, and download size.

## Audit And Trace Data

Tool audit events store metadata such as event time, event type, tool name,
status, risk level, permission names, and sanitized summaries. They are
intentionally not a full prompt or tool-argument log.

Agent trace and pending confirmation recovery are intentionally narrower than a
full execution replay. Pending rows persist only allowlisted request arguments,
redacted structure, and value-free checkpoint identifiers where possible.
Payload-bearing confirmations fail closed after restart instead of restoring
private executable payload values.

## Retention And Controls

Users can create, switch, and delete chat sessions in the app. Long-term memory
supports reviewing explicit records, forgetting individual records, and
clearing explicit memory records. Clearing app data or uninstalling the app
removes local app storage according to Android platform behavior.

This codebase does not contain a first-party analytics upload path beyond
user-configured remote model calls, recommended/custom model downloads, and
Android external intents initiated by confirmed actions. Recheck release builds
and any added SDKs before publishing this statement externally.
