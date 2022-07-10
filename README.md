# shade

A web application for allowing local and remote control of our
automated blinds, with support for guest access (control of guest room
and living room blinds) and date ranges, for when we have pet sitters.

In my setup, relies on Apache to terminate SSL using a
LetsEncrypt-managed certificate, for a subdomain that is reverse
proxied to a different port on localhost, so we only need to deal with
ordinary HTTP communication.

Offers a web socket used by a daemon running on our home network which
relays queries and commands to the blind controller software.

Originally generated using Luminus version "4.38"

## Prerequisites

You will need [Leiningen][1] 2.0 or above installed, and a recent Java
distribution, to build and run the Clojure web application.

The blind controller daemon requires `python3` and the
[websocket-client][2], [edn_format][3], [keyring][4], and
[pyControl4][5] libraries.

Currently, it is unable to retry when the connection to the server is
lost, so you need to wrap it in a mechanism that respawns it when it
dies.

[1]: https://github.com/technomancy/leiningen
[2]: https://pypi.org/project/websocket-client/
[3]: https://pypi.org/project/edn-format/
[4]: https://pypi.org/project/keyring/
[5]: https://pypi.org/project/pyControl4/

## Running

To start a web server for the application, run:

    lein run

For a deployed installation, you will want to build a standalone
überjar, using:

    lein uberjar

Then copy the resulting `target/uberjar/shade.jar` to somewhere like
`/usr/local/lib/shade.jar`, and configure it to be run as a system
daemon. Here is an example of a `systemd` configuration file which
does that on a modern Linux, `shade.service`:

    # See https://github.com/brunchboy/shade

    [Unit]
    Description=The Shade Web Application
    Wants=httpd-init.service
    After=network.target remote-fs.target nss-lookup.target httpd-init.service

    [Service]
    Type=simple
    Environment="DATABASE_URL=jdbc:postgresql:shade?user=shade&password=...elided..." "SHADE_WS_TOKEN=someSecretToken" "JAVA_TOOL_OPTIONS=-Xmx256m" "NREPL_PORT=7001"
    SuccessExitStatus=143
    ExecStart=/usr/bin/java -jar /usr/local/lib/shade.jar
    KillMode=process
    Restart=on-failure
    User=james
    Group=james

    [Install]
    WantedBy=multi-user.target

To start the blind controller daemon, which needs to run on the same
LAN as the Control4 Director appliance, you will need to set
environment variables `C4_USERNAME` and `C4_DIRECTOR_IP` to your
Control4 user name and the IP address of the Control4 Director
appliance on your local LAN. Your key ring will need to contain the
password for that account (under an entry for `control4.com`), and the
token you used passed as `SHADE_WS_TOKEN` above as the password for
the account `x-shade-token` under an entry for
`shade.deepsymmetry.org`. Then you can launch the daemon using:

    python3 src/python/client.py

Once again, you'll want to configure this to run automatically in the
background. Here is the macOS `launchd` agent file I use to run it as
an agent under my own login on a Mac Mini in our network rack, so it
has access to my system keychain for the above secrets,
`~/Library/LaunchAgents/org.deepsymmetry.shades-agent.plist`:

    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
    <dict>
        <key>EnvironmentVariables</key>
        <dict>
            <key>C4_DIRECTOR_IP</key>
            <string>192.168.1.150</string>
            <key>C4_USERNAME</key>
            <string>user@domain.org</string>
            <key>SHADE_WS_URL</key>
            <string>wss://shade.my.domain</string>
        </dict>
        <key>KeepAlive</key>
        <true/>
        <key>Label</key>
        <string>org.deepsymmetry.shaded</string>
        <key>ProgramArguments</key>
        <array>
            <string>/usr/local/bin/python3</string>
            <string>/Users/james/git/shade/src/python/client.py</string>
        </array>
        <key>RunAtLoad</key>
        <true/>
        <key>StandardErrorPath</key>
        <string>/tmp/org.deepsymmetry.shaded.stderr</string>
        <key>StandardOutPath</key>
        <string>/tmp/org.deepsymmetry.shaded.stdout</string>
    </dict>
    </plist>

Of course you will want to adjust the environment variable setting for
`C4_DIRECTOR_IP`, `C4_USERNAME`, and `SHADE_WS_URL` inside the
`EnvironmentVariables` `dict` to reflect your actual setup.

## License

Released under the MIT license, http://opensource.org/licenses/MIT

Copyright © 2022 James Elliott
