#
# Copyright 2014 Mimetic Markets, Inc.
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

from sputnik import config
from sputnik import observatory

debug, log, warn, error, critical = observatory.get_loggers("private")

from sputnik.plugin import PluginException
from sputnik.webserver.plugin import ServicePlugin, schema, authenticated, error_handler
from sputnik.exception import WebserverException

from twisted.internet.defer import inlineCallbacks, returnValue, succeed
from autobahn import wamp
from autobahn.wamp.types import RegisterOptions


class PrivateService(ServicePlugin):
    def __init__(self):
        ServicePlugin.__init__(self)

    @wamp.register(u"rpc.private.foobar")
    @error_handler
    @authenticated
    @schema("public/private.json#foobar")
    def foobar(self, x, username=None):
        log(x)
        r = yield succeed(x)
        returnValue(r)

    @inlineCallbacks
    def register(self, endpoint, procedure = None, options = None):
        results = yield ServicePlugin.register(self, endpoint, procedure, options=RegisterOptions(details_arg="details", discloseCaller=True))
        returnValue(results)
