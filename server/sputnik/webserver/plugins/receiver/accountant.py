#
# Copyright 2014 Mimetic Markets, Inc.
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

from sputnik import config
from sputnik import observatory

debug, log, warn, error, critical = observatory.get_loggers("accountant")

from sputnik.webserver.plugin import ReceiverPlugin
from sputnik.rpc.zmq_util import export, pull_share_async

class AccountantReceiver(ReceiverPlugin):
    def __init__(self):
        ReceiverPlugin.__init__(self)

    @export
    def fill(self, username, fill):
        log("Got 'fill' for %s / %s" % (username, fill))
        self.emit("fill", username, fill)

    @export
    def transaction(self, username, transaction):
        log("Got transaction for %s: %s" % (username, transaction))
        self.emit("transaction", username, transaction)

    @export
    def trade(self, ticker, trade):
        log("Got trade for %s: %s" % (ticker, trade))
        self.emit("trade", ticker, trade)

    @export
    def order(self, username, order):
        log("Got order for %s: %s" % (username, order))
        self.emit("order", username, order)

    def init(self):
        self.share = pull_share_async(self,
                config.get("webserver", "accountant_export"))

    def shutdown(self):
        # TODO: add shutdown code
        pass

