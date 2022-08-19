# shade

 <image align="right" width="585" height="1066"
 src="doc/assets/Room.PNG">
A web application for allowing local and remote control of automated
blinds.

Uses a set of four photographs of each room, with the blackout and
screen shades all open or closed, to create a composite image showing
the current state of the room, and allowing users to tap on the photo
to specify the positions to which they want specific blinds to move.
Also allows the definition of macros to quickly move groups of blinds
to specific positions. Macros can be run globally or within individual
rooms.

Provides support for guest access (control of specific rooms, or
periodic activation for pet sitters).

In my setup, relies on Apache to terminate SSL using a
LetsEncrypt-managed certificate, for a subdomain that is reverse
proxied to a different port on localhost, so we only need to deal with
ordinary HTTP communication.

Offers a web socket used by a daemon running on our home network which
relays queries and commands to the blind controller software.

 <image align="left" width="585" height="1200"
 src="doc/assets/Status.PNG">

Incorporates astronomical algorithms and open weather data so that
blinds can be closed on warm days during the part of the day where the
sun is shining through them, and can allow you to go to sleep enjoying
cityscape views, but close the blinds at astronomical dawn so you
don't get awakened earlier than you want.

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
    Environment="DATABASE_URL=jdbc:postgresql:shade?user=shade&password=...elided..." "WEBSOCKET_TOKEN=...elided..."  "OPENWEATHER_API_KEY=...elided..." "JAVA_TOOL_OPTIONS=-Xmx256m" "NREPL_PORT=7001"
    SuccessExitStatus=143
    ExecStart=/usr/bin/java -Dconf=/usr/local/etc/shade.edn -jar /usr/local/lib/shade.jar
    KillMode=process
    Restart=on-failure
    User=james
    Group=james

    [Install]
    WantedBy=multi-user.target

Because this file contains secrets such as passwords and tokens, you
should make sure it is readable only by root. And as you may have
noticed within it, there is a reference to a less-sensitive
configuration file that is read by the web server at startup. In my
case that is located at `/usr/local/etc/shade.edn`, and has the
following contents:

```clojure
{:location {:latitude  43.07555555555556
            :longitude -89.38611111111112
            :timezone  "America/Chicago"}
 :cdn-url  "https://d22vjwe5tlkwmh.cloudfront.net/shade"}
```

This reflects the location and timezone to be used for astronomical
calculations, and the CloudFront content delivery network used to
efficiently serve the room images. You can skip using a CDN like that
and embed the images directly in the `img` subdirectory of the
`resources` folder, so they will be served by the web application
directly. If you do that, simply update the `:cdn-url` to point at
that path within your web application.

To start the blind controller daemon, which needs to run on the same
LAN as the Control4 Director appliance, you will need to set
environment variables `C4_USERNAME` and `C4_DIRECTOR_IP` to your
Control4 user name and the IP address of the Control4 Director
appliance on your local LAN. Your key ring will need to contain the
password for that account (under an entry for `control4.com`), and the
token you used passed as `WEBSOCKET_TOKEN` above as the password for
the account `x-shade-token` under an entry for
`shade.deepsymmetry.org`. Then you can launch the daemon using:

    python3 src/python/client.py

Once again, you'll want to configure this to run automatically in the
background. Here is the macOS `launchd` agent file I use to run it as
an agent under my own login on a Mac Mini in our network rack, so it
has access to my system keychain for the above secrets,
`~/Library/LaunchAgents/org.deepsymmetry.shades-agent.plist`:

```xml
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
```

Of course you will want to adjust the environment variable setting for
`C4_DIRECTOR_IP`, `C4_USERNAME`, and `SHADE_WS_URL` inside the
`EnvironmentVariables` `dict` to reflect your actual setup.

## Development

For development, you will want to bring up the application in a local
REPL for rapid testing of ideas. To facilitate that, you can put the
following content into the file `dev-config.edn` at the top level of
the project, and update it to reflect your setup. This is used only
for local development and is not committed to git.

As noted above, you can serve the room images directly from inside
your web application rather than setting up a content distribution
network to serve them if you want to keep things simple.

You will need to obtain your own free API key for OpenWeather,
however. You can share that between development and production; the
limits on a free account are far higher than Shade needs.

```clojure
;; WARNING:
;;
;; The dev-config.edn file is used for local environment variables,
;; such as database credentials. This file is listed in .gitignore and
;; will be excluded from version control by Git.

{:dev        true
 :port       3000
 ;; when :nrepl-port is set the application starts the nREPL server on load
 :nrepl-port 7000

 :location {:latitude  43.07555555555556
            :longitude -89.38611111111112
            :timezone  "America/Chicago"}

 ;; set your dev database connection URL here
 :database-url "jdbc:postgresql://localhost/shade?user=shade&password=...elided..."

;; Set the URL from which large images and other resources are served here
 :cdn-url "https://d22vjwe5tlkwmh.cloudfront.net/shade"

 ;; This controls the value of the special x-shade-token header that is required to open a web socket connection.
 :websocket-token "...elided..."

 ;; The API key used to access OpenWeather forecast and current conditions information.
 :openweather-api-key "...elided..."
 }
```

Once again, because this file contains secrets, you will want to make
sure it is readable only by your account.

## License

Released under the MIT license, http://opensource.org/licenses/MIT

Originally generated using Luminus version 4.38.

Copyright © 2022 James Elliott
