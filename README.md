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

You will need [Leiningen][1] 2.0 or above installed.

[1]: https://github.com/technomancy/leiningen

## Running

To start a web server for the application, run:

    lein run

## License

Released under the MIT license, http://opensource.org/licenses/MIT

Copyright © 2022 James Elliott
