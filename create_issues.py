import json
import subprocess
import sys

TOKEN = subprocess.run(['bash', '-c', 'echo $GITHUB_COPILOT_API_TOKEN'], capture_output=True, text=True).stdout.strip()

def call_mcp(method, params, req_id):
    payload = json.dumps({
        "jsonrpc": "2.0",
        "method": method,
        "params": params,
        "id": req_id
    })
    result = subprocess.run(
        ['curl', '-s', '-X', 'POST',
         '-H', f'Authorization: Bearer {TOKEN}',
         '-H', 'Content-Type: application/json',
         '-d', payload,
         'https://api.individual.githubcopilot.com/mcp'],
        capture_output=True, text=True
    )
    for line in result.stdout.split('\n'):
        if line.startswith('data: '):
            try:
                return json.loads(line[6:])
            except:
                pass
    return None

def create_issue(title, body, labels, req_id):
    return call_mcp("tools/call", {
        "name": "issue_write",
        "arguments": {
            "method": "create",
            "owner": "majuwa",
            "repo": "KrhnlesImageManagement",
            "title": title,
            "body": body,
            "labels": labels
        }
    }, req_id)

def update_issue(issue_number, title, body, labels, state, req_id):
    return call_mcp("tools/call", {
        "name": "issue_write",
        "arguments": {
            "method": "update",
            "owner": "majuwa",
            "repo": "KrhnlesImageManagement",
            "issue_number": issue_number,
            "title": title,
            "body": body,
            "labels": labels,
            "state": state
        }
    }, req_id)

# Update issue #12 (test) to be the SettingsViewModel bug
print("Updating issue #12...")
r = update_issue(12, 
    "[Bug] SettingsViewModel.testConnection() uses collect instead of first(), causing a coroutine leak",
    """## Bug Description
In `SettingsViewModel.testConnection()`, the code calls `credentialStore.webDavConfig.collect { ... return@collect }`. The `collect` lambda uses `return@collect` to exit after the first emission, but this does **not** cancel the upstream Flow — the coroutine launched by `viewModelScope.launch` remains blocked until the ViewModel is cleared.

The correct approach is to call `.first()` to take a single emission and then complete.

## Reproduction
1. Open the Settings screen
2. Tap "Test Connection"
3. Navigate away — the viewModelScope launch holding the `collect` survives until ViewModel death

## Expected Behaviour
`testConnection()` reads the current config once (`.first()`), tests the connection, and returns.

## Fix
Replace:
```kotlin
credentialStore.webDavConfig.collect { config ->
    ...
    return@collect
}
```
With:
```kotlin
val config = credentialStore.webDavConfig.first()
...
```

## Impact
- Memory/coroutine leak per invocation of "Test Connection" until the ViewModel is destroyed
- Could cause concurrent test-connection calls if the user taps quickly""",
    ["bug"], "open", 100)
print("Issue #12 updated:", r)
