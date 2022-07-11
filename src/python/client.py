#!/usr/bin/env python3
#
# Daemon for coordinating between Control4 blinds controller on the
# home LAN and the shade web application in the cloud over a web
# socket.
#
# Requires:
#   websocket-client, https://pypi.org/project/websocket-client/
#   edn_format, https://pypi.org/project/edn-format/
#   keyring, https://pypi.org/project/keyring/
#   pyControl4, https://pypi.org/project/pyControl4/

import websocket
import _thread
import time
import keyring
import os
import asyncio
import json
import edn_format

from pyControl4.account import C4Account
from pyControl4.director import C4Director
from pyControl4.blind import C4Blind
from pyControl4.error_handling import BadCredentials, BadToken


# See https://github.com/home-assistant/core/blob/dev/homeassistant/components/control4/__init__.py
# and https://github.com/home-assistant/core/blob/dev/homeassistant/components/control4/director_utils.py
async def login_to_director() -> None:
    """Set up communication with Control4."""
    username = os.environ["C4_USERNAME"]
    # TODO: This should perhaps be switched to use environment
    #       variables too for more robustness against python upgrades
    #       (i.e. to avoid having to give keychain permissions to the
    #       new version of python).
    account = C4Account(username, keyring.get_password("control4.com", username))
    try:
        await account.getAccountBearerToken()
    except BadCredentials as exception:
        print("Error authenticating with Control4 account API, incorrect username or password: {}".format(exception))
        return
    controllers = await account.getAccountControllers()
    director_bearer_token = await account.getDirectorBearerToken(controllers["controllerCommonName"])
    global director
    director = C4Director(os.environ["C4_DIRECTOR_IP"], director_bearer_token["token"])
    print("Logged in to Control4 director, token expires: {}".format(director_bearer_token["token_expiration"]))


asyncio.run(login_to_director())

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
    try:
        return await blind.setLevelTarget(spec.get(kw_level))
    except BadToken:
        print("Updating Control4 director token")
        await login_to_director()
        return await blind.setLevelTarget(spec.get(kw_level))

async def set_levels(ws, blinds):
    await asyncio.gather(*map(set_level, blinds))
    ws.send(edn_format.dumps({kw_action: kw_set_levels}))

async def report_status(ws, blinds):
    result = {}
    for id in blinds:
        blind = C4Blind(director, id)
        try:
            result[id] = { kw_level: await blind.getLevel(), kw_stopped: await blind.getStopped()}
        except BadToken:
            print("Updating Control4 director token")
            await login_to_director()
            result[id] = { kw_level: asyncio.run(blind.getLevel()), kw_stopped: asyncio.run(blind.getStopped())}
    ws.send(edn_format.dumps({kw_action: kw_status, kw_blinds: result}))

def on_message(ws, message):
    parsed = edn_format.loads(message)
    action = parsed.get(kw_action)

    if action == kw_status:
        asyncio.run(report_status(ws, parsed.get(kw_blinds)))
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
    ws_url = os.environ.get("SHADE_WS_URL", "wss://shade.deepsymmetry.org/ws")
    print("Opening web socket to " + ws_url)
    ws = websocket.WebSocketApp(ws_url,
                                on_open=on_open,
                                on_message=on_message,
                                on_error=on_error,
                                on_close=on_close,
                                header={"x-shade-token": token})

    ws.run_forever()
