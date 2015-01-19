from sputnik import config
from sputnik import observatory
from sputnik import util

debug, log, warn, error, critical = observatory.get_loggers("feeds_user")

from sputnik.plugin import PluginException
from sputnik.webserver.plugin import ServicePlugin
from datetime import datetime
from sputnik import util

from twisted.internet.defer import inlineCallbacks, returnValue, gatherResults
from autobahn import wamp

class UserAnnouncer(ServicePlugin):
    def on_fill(self, username, fill):
        username = util.encode_username(username)
        self.publish(u"feeds.user.fills.%s" % username, fill)

    def on_transaction(self, username, transaction):
        username = util.encode_username(username)
        self.publish(u"feeds.user.transactions.%s" % username, transaction)

    def on_order(self, username, order):
        username = util.encode_username(username)
        self.publish(u"feeds.user.orders.%s" % username, order)

