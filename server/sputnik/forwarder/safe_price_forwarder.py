#!/usr/bin/env python
#
# Copyright 2014 Mimetic Markets, Inc.
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

from sputnik import config
from optparse import OptionParser
parser = OptionParser()
parser.add_option("-c", "--config", dest="filename",
    help="config file", default="../config/sputnik.ini")
(options, args) = parser.parse_args()
if options.filename:
    config.reconfigure(options.filename)

__author__ = 'satosushi'

"""
A small fowarding service, every engine publishes their safe price to the frontend
and every safe price consumer subscribes to this device.
"""

from sputnik.rpc.zmq_util import bind_subscriber, bind_publisher
from twisted.internet import reactor
from twisted.python import log
import json

def main():
    import sys
    log.startLogging(sys.stdout)
    subscriber = bind_subscriber(config.get("safe_price_forwarder", "zmq_frontend_address"))
    publisher = bind_publisher(config.get("safe_price_forwarder", "zmq_backend_address"))

    subscriber.subscribe("")

    safe_prices = {}
    def onPrice(*args):
        update = json.loads(args[0])
        log.msg("received update: %s" % update)
        safe_prices.update(update)
        publisher.publish(json.dumps(safe_prices), tag=b'')

    subscriber.gotMessage = onPrice
    reactor.run()

