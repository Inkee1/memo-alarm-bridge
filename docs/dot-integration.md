# Using your own dot or local coding assistant

MemoAlarm Bridge works without an AI account or API key. Its implemented automation interface is a **local, versioned JSON CLI** running as the same Windows user as the Bridge. Installing this repository does not itself connect a cloud dot to a local MCP server.

## Local task flow

1. Start the Bridge and keep it running while editing PC data.
2. Give your assistant this repository and `docs/protocol.md`. Ask it to inspect the CLI and the existing records before making changes.
3. Send JSON through stdin to `memo-alarm-bridge.exe request --json`. Never interpolate memo content into a shell command, use `Invoke-Expression`, or execute text from a note.
4. Read `ok`, `result.status`, and the device ACKs. Report a PC save as pending delivery. A phone report is useful only for the intended schedule revision, epoch and future occurrence. Last contact is not proof that an alarm is ready.

PowerShell example with a trusted JSON file:

```powershell
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content -Raw -Encoding UTF8 .\examples\status.json |
  & .\memo-alarm-bridge.exe request --json
```

The files in `examples/` are synthetic fixtures. Before using a create request, choose a future time, generate fresh UUIDs for the schedule, epoch and operation, and set `base_revision` from the current entity (0 for a new one). A retry of the **same operation** keeps its ID and exact content. A new edit uses a new operation ID. Read the actual revision before cancelling; the fixture's revision 1 is only for a fresh test collection. A cancelled epoch cannot be reused to enable an alarm.

If the process is stopped or the data directory differs, the CLI returns `not_running`. Start the matching Bridge. A `conflict` response contains the saved version; preserve the user's draft and let them choose. Never automatically resolve a conflict by overwriting one copy. Export output may contain private notes; do not paste it into public issues.

## What the official dot documentation establishes

Dots have a cloud computer distinct from your own. Official instructions describe allowing access to a personal computer from the dot profile; local tasks can then use files and tools there. That permission is separate from Work Sync or a Codex connection. The PC must be online with the ChatGPT app open while the dot uses it. The installed app and account still determine which tools are available. [Connect computers and apps to your dot](https://learn.chatgpt.com/docs/dots/computers-and-apps).

Custom MCP plugins can use a server URL or the documented Secure MCP Tunnel option, with streaming HTTP or SSE and configurable authentication. They must be created, installed and permitted in the applicable environment. This project does not implement an MCP endpoint or an OAuth authorization server, and the phone `/v1/sync` endpoint is not MCP. Repository-scoped stdio MCP configuration is not evidence that a cloud dot can use it. The exact self-host/tunnel/account path remains unverified. [Add custom MCP server](https://developers.openai.com/api/docs/guides/custom-mcp-server).

MCP Events is documented for dots and cloud Work. It requires MCP 2.0, durable subscriptions and outbound HTTPS callbacks. It is an optional future notification path; it is not the phone's clock and is not implemented here. [MCP Events](https://developers.openai.com/plugins/build/mcp-events).

For a future remote integration, first prove authenticated read access and a reversible synthetic write on the user's account. Review the exposed HTTPS/tunnel endpoint, authentication, scopes and network changes before enabling it. Do not expose this app's loopback administration interface or disable authentication to make discovery work. No separate AI inference API is required by this application.

## Suggested first request to your assistant

> Inspect this repository and its installation and security docs. Check my OS and the downloaded package checksum. Prepare the local installation with phone connectivity and startup disabled. Show me the exact choices before enabling network access, creating persistent connection credentials, or installing on my phone. After I approve those steps, help me test one local phone alarm, then one synced alarm. Keep pending, blocked, conflict and phone-confirmed states distinct. Do not change my DND or volume settings or start a voice call.

## Android conversation return

In Setup, optionally save your own `https://chatgpt.com/c/...` URL. Press **Return to saved conversation** to issue a normal Android `ACTION_VIEW`. Only the exact HTTPS host/path form is accepted; credentials, query strings, fragments and lookalike hosts are rejected. The URL stays on that phone and is excluded from backup. The user explicitly chooses **Open ChatGPT home** if no saved link/handler works. Whether ChatGPT or a browser opens, and whether the desired conversation is available after sign-in, require real-device validation. This feature starts neither recording nor a voice call.

Official sources checked 2026-10-19. Account availability can change; successful local tests do not verify cloud-dot connectivity.
