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
import os

def on_message(ws, message):
    print(message)

def on_error(ws, error):
    print(error)

def on_close(ws, close_status_code, close_msg):
    print("### closed ###")

def on_open(ws):
    print("Opened connection")

if __name__ == "__main__":
    websocket.enableTrace(True)
    ws = websocket.WebSocketApp("ws://localhost:3000/ws",  # wss://shade.brunchboy.com/ws
                                on_open=on_open,
                                on_message=on_message,
                                on_error=on_error,
                                on_close=on_close,
                                header={"x-shade-token": os.environ["SHADE_TOKEN"]})

    ws.run_forever(dispatcher=rel)  # Set dispatcher to automatic reconnection
    rel.signal(2, rel.abort)  # Keyboard Interrupt
    rel.dispatch()
#    ws.send("sent a message from python!")
    ws.close()
