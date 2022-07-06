#!/usr/bin/env python3
#
# Daemon for coordinating between Control4 blinds controller on the
# home LAN and the shade web application in the cloud over a web
# socket.
#
# Requires:
#   websocket-client, https://pypi.org/project/websocket-client/
#   rel, https://pypi.org/project/rel/
#   edn_format, https://pypi.org/project/edn-format/
#   pyControl4, https://pypi.org/project/pyControl4/

import websocket
import _thread
import time
import rel
import keyring
import os
import asyncio
import json
import edn_format

from pyControl4.account import C4Account
from pyControl4.director import C4Director
from pyControl4.blind import C4Blind

username = os.environ["C4_USERNAME"]
# TODO: This should perhaps be switched to use environment variables too for more robustness against python upgrades
#       (i.e. to avoid having to give keychain permissions to the new version of python).
account = C4Account(username, keyring.get_password("control4.com", username))
asyncio.run(account.getAccountBearerToken())
controllers = asyncio.run(account.getAccountControllers())

director_bearer_token = asyncio.run(
    account.getDirectorBearerToken(controllers["controllerCommonName"])
)

director = C4Director(os.environ["C4_DIRECTOR_IP"], director_bearer_token["token"])

kw_action = edn_format.Keyword("action")
kw_blinds = edn_format.Keyword("blinds")
kw_details = edn_format.Keyword("details")
kw_error = edn_format.Keyword("error")
kw_id = edn_format.Keyword("id")
kw_level = edn_format.Keyword("level")
kw_message = edn_format.Keyword("message")
kw_set_levels = edn_format.Keyword("set-levels")
kw_status = edn_format.Keyword("status")
kw_stopped = edn_format.Keyword("stopped")


async def set_level(spec):
    blind = C4Blind(director, spec.get(kw_id))
    return await blind.setLevelTarget(spec.get(kw_level))

async def set_levels(ws, blinds):
    await asyncio.gather(*map(set_level, blinds))
    ws.send(edn_format.dumps({kw_action: kw_set_levels}))

def report_status(ws, blinds):
    result = {}
    for id in blinds:
        blind = C4Blind(director, id)
        result[id] = { kw_level: asyncio.run(blind.getLevel()), kw_stopped: asyncio.run(blind.getStopped())}
    ws.send(edn_format.dumps({kw_action: kw_status, kw_blinds: result}))

def on_message(ws, message):
    parsed = edn_format.loads(message)
    action = parsed.get(kw_action)

    if action == kw_status:
        report_status(ws, parsed.get(kw_blinds))
    elif action == kw_set_levels:
        asyncio.run(set_levels(ws, parsed.get(kw_blinds)))
    else:
        ws.send(edn_format.dumps({kw_action: kw_error, kw_message: "Unknown action", kw_details: action}))

    print("Processed: " + message)

def on_error(ws, error):
    print(error)

def on_close(ws, close_status_code, close_msg):
    print("### closed ###")

def on_open(ws):
    print("Opened connection")

if __name__ == "__main__":
    websocket.enableTrace(True)
    token = keyring.get_password("shade.deepsymmetry.org", "x-shade-token")
    # For local development/testing, set SHADE_WS_URL to "ws://localhost:3000/ws"
    ws = websocket.WebSocketApp(os.environ.get("SHADE_WS_URL", "wss://shade.deepsymmery.org/ws"),
                                on_open=on_open,
                                on_message=on_message,
                                on_error=on_error,
                                on_close=on_close,
                                header={"x-shade-token": token})

    ws.run_forever(dispatcher=rel)  # Set dispatcher to automatic reconnection
    rel.signal(2, rel.abort)  # Keyboard Interrupt
    rel.dispatch()
#    ws.send("sent a message from python!")
    ws.close()
