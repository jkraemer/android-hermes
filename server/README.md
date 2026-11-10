# hermes-app plugin

A Hermes plugin that pushes events to the Hermes Android app over WebPush (UnifiedPush):

- finished cron reports (silent runs are skipped, `[CRON_FAILURE]` reports are marked failed),
- approval requests and questions from the agent, and a note when one is resolved,
- reply-finished notices for the chats the app follows.

Every push body is encrypted for the app's keys (RFC 8291 `aes128gcm`); the push service only sees ciphertext.
The app registers its subscription and followed sessions through the plugin's dashboard routes
(`/api/plugins/hermes-app`), which sit behind the dashboard's own authentication.

## Install

For a Hermes installed on the host with pip/uv:

    ./install.sh              # into $HERMES_HOME, default ~/.hermes
    ./install.sh /path/to/hermes-home

Then enable the plugin and restart **both** the dashboard and the gateway. They are separate processes that each
load the plugin: the dashboard serves the routes and runs the app's chat turns, the gateway runs cron.

    hermes plugins enable hermes-app
    # restart `hermes dashboard` and `hermes gateway`

(Instead of the first command, add `hermes-app` to `plugins.enabled` in `config.yaml`.)
The plugin needs nothing beyond what Hermes ships: the standard library, `cryptography`, `httpx` and `fastapi`.

The app's test notification only proves the dashboard side (routes, subscription, push service). Cron pushes come
from the gateway, which loads the plugin on its own, so a gateway that was not restarted after install or enable
sends none even though the test notification arrives.

## Push service

Pushes go through whatever endpoint the UnifiedPush distributor gives the app; with ntfy.sh that is a public
topic URL. The content is encrypted, so the topic does not reveal it, but anyone who learns the topic URL sees
that a push happened, when, and roughly how large it was. A private ntfy instance
later should not allow reads of the topics, e.g. `ntfy access '*' 'up*' write-only`.

## Tests

    cd server && uv run pytest
