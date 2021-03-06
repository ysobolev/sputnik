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
                  help="config file", default=None)
(options, args) = parser.parse_args()
if options.filename:
    config.reconfigure(options.filename)

import sys
import json
import heapq
import math

from sputnik.database import database
from sputnik.database import models
from sputnik.accountant import accountant

from sputnik.util.conversions import dt_to_timestamp
from sputnik.util.debug import timed
from sputnik.util.util import get_uid

from twisted.internet import reactor
from twisted.python import log
from sputnik.rpc.zmq_util import export, router_share_async, push_proxy_async, ComponentExport, connect_publisher
from sputnik.rpc.rpc_schema import schema
from collections import defaultdict
from datetime import datetime
from sputnik.watchdog import watchdog


class OrderSide:
    BUY = -1
    SELL = 1

    @staticmethod
    def name(n):
        if n == -1:
            return "BUY"
        return "SELL"


class Order:
    def __init__(self, id=None, contract=None, quantity=None,
                 quantity_left=None, price=None, side=None, username=None,
                 timestamp=None):
        self.id = id
        self.contract = contract
        self.quantity = quantity
        self.quantity_left = quantity
        self.price = price
        self.side = side
        self.username = username
        if timestamp is not None:
            self.timestamp = timestamp
        else:
            self.timestamp = dt_to_timestamp(datetime.utcnow())

    def to_administrator(self):
        return {'id': self.id,
                'quantity': self.quantity,
                'quantity_left': self.quantity_left,
                'price': self.price,
                'username': self.username,
                'timestamp': self.timestamp,
                'errors': ""}

    def matchable(self, other):
        if self.side == other.side:
            return False
        return (self.price - other.price) * self.side <= 0

    def __str__(self):
        return "%sOrder(price=%s, quantity=%s/%s, id=%d)" % (
            "Bid" if self.side < 0 else "Ask", self.price, self.quantity_left, self.quantity, self.id)

    def __repr__(self):
        return self.__dict__.__repr__()

    def __eq__(self, other):
        return self.side == other.side and self.price == other.price \
            and self.timestamp == other.timestamp

    def __lt__(self, other):
        """
        Returns whether an order is higher than another in the order book.
        """

        if self.side is not other.side:
            raise Exception("Orders are not comparable.")

        # Price-Time Priority
        return (self.side * self.price, self.timestamp) < (other.side * other.price, other.timestamp)


class EngineListener:
    def on_init(self):
        pass

    def on_shutdown(self):
        pass

    def on_queue_success(self, order):
        pass

    def on_queue_fail(self, order, reason):
        pass

    def on_trade_success(self, order, passive_order, price, quantity):
        pass

    def on_trade_fail(self, order, passive_order, reason):
        pass

    def on_cancel_success(self, order):
        pass

    def on_cancel_fail(self, order_id, reason):
        pass


class Engine:
    def __init__(self):
        self.orderbook = {OrderSide.BUY: [], OrderSide.SELL: []}
        self.ordermap = {}
        self.listeners = []

    @timed
    def place_order(self, order):

        # Loop until the order or the opposite side is exhausted.
        while order.quantity_left > 0:

            # If the other side has run out of orders, break.
            if not self.orderbook[-order.side]:
                break

            # Find the best counter-offer.
            passive_order = self.orderbook[-order.side][0]

            # We may assume this order is the best offer on its side. If not,
            #   the following will automatically fail since it failed for
            #   better offers already.

            # If the other side's best order is too pricey, break.
            if not order.matchable(passive_order):
                break

            # Trade.
            price, quantity = self.match(order, passive_order)

            # If the passive order is used up, remove it.
            if passive_order.quantity_left <= 0:
                heapq.heappop(self.orderbook[passive_order.side])
                del self.ordermap[passive_order.id]

            # Notify listeners.
            self.notify_trade_success(order, passive_order, price, quantity)

        # If order is not completely filled, push remainder onto heap and make
        #   an entry in the map.
        if order.quantity_left > 0:
            heapq.heappush(self.orderbook[order.side], order)
            self.ordermap[order.id] = order

            # Notify listeners
            self.notify_queue_success(order)

        # Order has been successfully processed.
        return True

    def match(self, order, passive_order):

        # Calculate trading quantity and price.
        quantity = min(order.quantity_left, passive_order.quantity_left)
        price = passive_order.price

        # Adjust orders on the books
        order.quantity_left -= quantity
        passive_order.quantity_left -= quantity

        return price, quantity

    @timed
    def cancel_order(self, id):
        # Check to make sure order has not already been filled.
        if id not in self.ordermap:
            # Too late to cancel.
            log.msg("The order id=%s cannot be cancelled, it's already outside the book." % id)
            self.notify_cancel_fail(id, "the order is no longer on the book")
            return False

        # Find the order object.
        order = self.ordermap[id]

        # Remove the order from the book.
        del self.ordermap[id]
        self.orderbook[order.side].remove(order)
        heapq.heapify(self.orderbook[order.side]) #yuck

        # Notify user of cancellation.
        self.notify_cancel_success(order)

        return True

    def add_listener(self, listener):
        self.listeners.append(listener)

    def remove_listener(self, listener):
        if listener in self.listeners:
            self.listeners.remove(listener)

    def notify_init(self):
        for listener in self.listeners:
            try:
                listener.on_init()
            except Exception, e:
                log.err("Exception in on_init of %s: %s." % (listener, e))
                log.err()

    def notify_shutdown(self):
        for listener in self.listeners:
            try:
                listener.on_shutdown()
            except Exception, e:
                log.err("Exception in on_shutdown of %s: %s." % (listener, e))
                log.err()

    def notify_queue_success(self, order):
        for listener in self.listeners:
            try:
                listener.on_queue_success(order)
            except Exception, e:
                log.err("Exception in on_queue_success of %s: %s." % (listener, e))
                log.err()

    def notify_queue_fail(self, order, reason):
        for listener in self.listeners:
            try:
                listener.on_queue_fail(order, reason)
            except Exception, e:
                log.err("Exception in on_queue_fail of %s: %s." % (listener, e))
                log.err()

    def notify_trade_success(self, order, passive_order, price, quantity):
        for listener in self.listeners:
            try:
                listener.on_trade_success(order, passive_order, price, quantity)
            except Exception, e:
                log.err("Exception in on_trade_success of %s: %s." % (listener, e))
                log.err()

    def notify_trade_fail(self, order, passive_order, reason):
        for listener in self.listeners:
            try:
                listener.on_trade_fail(order, passive_order, reason)
            except Exception, e:
                log.err("Exception in on_trade_fail of %s: %s." % (listener, e))
                log.err()

    def notify_cancel_success(self, order):
        for listener in self.listeners:
            try:
                listener.on_cancel_success(order)
            except Exception, e:
                log.err("Exception in on_cancel_success of %s: %s." % (listener, e))
                log.err()

    def notify_cancel_fail(self, order, reason):
        for listener in self.listeners:
            try:
                listener.on_cancel_fail(order, reason)
            except Exception, e:
                log.err("Exception in on_cancel_fail of %s: %s." % (listener, e))
                log.err()


class LoggingListener:
    def __init__(self, engine, contract):
        self.engine = engine
        self.contract = contract

    def on_init(self):
        self.ticker = self.contract.ticker
        log.msg("Engine for contract %s (%d) started." % (self.ticker, self.contract.id))
        log.msg(
            "Listening for connections on port %d." % (config.getint("engine", "accountant_base_port") + self.contract.id))
        log.msg(
            "Listening for connections on port %d." % (config.getint("engine", "administrator_base_port") + self.contract.id))


    def on_shutdown(self):
        log.msg("Engine for contract %s stopped." % self.ticker)

    def on_queue_success(self, order):
        log.msg("%s queued." % order)
        self.print_order_book()

    def on_queue_fail(self, order, reason):
        log.msg("%s cannot be queued because %s." % (order, reason))

    def on_trade_success(self, order, passive_order, price, quantity):
        log.msg("Successful trade between order id=%s and id=%s for %s lots at %s each." % (
            order.id, passive_order.id, quantity, price))
        self.print_order_book()

    def on_trade_fail(self, order, passive_order, reason):
        log.msg("Cannot complete trade between %s and %s." % (order, passive_order))

    def on_cancel_success(self, order):
        log.msg("%s cancelled." % order)
        self.print_order_book()

    def on_cancel_fail(self, order, reason):
        log.msg("Cannot cancel %s because %s." % (order, reason))

    def print_order_book(self):
        log.msg("Orderbook for %s:" % self.contract.ticker)
        log.msg("Bids                   Asks")
        log.msg("Vol.  Price     Price  Vol.")
        length = max(len(self.engine.orderbook[OrderSide.BUY]), len(self.engine.orderbook[OrderSide.SELL]))
        for i in range(length):
            try:
                ask = self.engine.orderbook[OrderSide.SELL][i]
                ask_str = "{:<5} {:<5}".format(ask.price, ask.quantity_left)
            except:
                ask_str = "           "
            try:
                bid = self.engine.orderbook[OrderSide.BUY][i]
                bid_str = "{:>5} {:>5}".format(bid.quantity_left, bid.price)
            except:
                bid_str = "           "
            log.msg("{}     {}".format(bid_str, ask_str))


class AccountantNotifier(EngineListener):
    def __init__(self, engine, accountant, contract):
        self.engine = engine
        self.accountant = accountant
        self.contract = contract
        self.ticker = self.contract.ticker

    def on_init(self):
        pass

    def on_trade_success(self, order, passive_order, price, quantity):
        uid = get_uid()
        self.accountant.post_transaction(order.username,
                                         {
                                             'username': order.username,
                                             'aggressive': True,
                                             'contract': self.ticker,
                                             'order': order.id,
                                             'other_order': passive_order.id,
                                             'side': OrderSide.name(order.side),
                                             'quantity': quantity,
                                             'price': price,
                                             'timestamp': order.timestamp,
                                             'uid': uid
                                         }
        )

        self.accountant.post_transaction(passive_order.username,
                                         {
                                             'username': passive_order.username,
                                             'aggressive': False,
                                             'contract': self.ticker,
                                             'order': passive_order.id,
                                             'other_order': order.id,
                                             'side': OrderSide.name(passive_order.side),
                                             'quantity': quantity,
                                             'price': price,
                                             'timestamp': order.timestamp,
                                             'uid': uid
                                         }
        )


class WebserverNotifier(EngineListener):
    def __init__(self, engine, webserver, contract, reg_publish=True):
        self.engine = engine
        self.webserver = webserver
        self.contract = contract
        self.aggregated_book = {"bids": defaultdict(int), "asks": defaultdict(int)}
        self.side_map = { OrderSide.BUY: "bids",
                          OrderSide.SELL: "asks"}

        # Publish every 10 min no matter what
        if reg_publish:
            def regular_publish():
                self.publish_book()
                reactor.callLater(600, regular_publish)

            reactor.callLater(600, regular_publish)

    def on_init(self):
        self.publish_book()

    def on_trade_success(self, order, passive_order, price, quantity):
        side = self.side_map[passive_order.side]

        self.aggregated_book[side][passive_order.price] -= quantity
        if self.aggregated_book[side][passive_order.price] == 0:
            del self.aggregated_book[side][passive_order.price]

        self.publish_book()

    def on_queue_success(self, order):
        side = self.side_map[order.side]

        self.aggregated_book[side][order.price] += order.quantity_left
        self.publish_book()

    def on_cancel_success(self, order):
        side = self.side_map[order.side]

        self.aggregated_book[side][order.price] -= order.quantity_left
        if self.aggregated_book[side][order.price] == 0:
            del self.aggregated_book[side][order.price]

        self.publish_book()

    @property
    def wire_book(self):
        wire_book = {"contract": self.contract.ticker,
                     "bids": [{"quantity": row[1],
                               "price": row[0]} for row in self.aggregated_book["bids"].iteritems()],
                     "asks": [{"quantity": row[1],
                               "price": row[0]} for row in self.aggregated_book["asks"].iteritems()]}
        return wire_book

    def publish_book(self):
        self.webserver.book(self.contract.ticker, self.wire_book)


class SafePriceNotifier(EngineListener):
    def __init__(self, session, engine, accountant, webserver, forwarder, contract):
        self.session = session
        self.engine = engine
        self.contract = contract
        self.forwarder = forwarder
        self.accountant = accountant
        self.webserver = webserver

        self.ema_price_volume = 0
        self.ema_volume = 0
        self.ema_timestamp = None
        self.decay = 0.999
        self.safe_price = None
        # Publish every 10 min no matter what
        def regular_publish():
            self.publish_safe_price()
            reactor.callLater(600, regular_publish)

        reactor.callLater(600, regular_publish)

    def on_init(self):
        try:
            trades = self.session.query(models.Trade).filter_by(contract=contract).order_by(
                models.Trade.timestamp)
    
            for trade in trades:
                self.update_safe_price(trade.price, trade.quantity, publish=False, timestamp=trade.timestamp)
            if self.safe_price is None:
                self.safe_price = 42
                log.msg(
                        "warning, missing last trade for contract: %s. Using 42 as a stupid default" % self.contract.ticker)
        except Exception as e:
            self.session.rollback()
            raise e

        self.publish_safe_price()

    def on_trade_success(self, order, passive_order, price, quantity):
        self.update_safe_price(price, quantity)

    def update_safe_price(self, price, quantity, publish=True, timestamp=None):
        if timestamp is None:
            timestamp = datetime.utcnow()

        if self.ema_timestamp is None:
            decay = self.decay
        else:
            seconds = (timestamp - self.ema_timestamp).total_seconds()
            decay = math.pow(self.decay, seconds)

        self.ema_timestamp = timestamp
        self.ema_volume = decay * self.ema_volume + (1 - decay) * quantity
        self.ema_price_volume = decay * self.ema_price_volume + (1 - decay) * quantity * price
        self.safe_price = int(self.ema_price_volume / self.ema_volume)

        if publish:
            self.publish_safe_price()

    def publish_safe_price(self):
        self.accountant.safe_prices(None, self.contract.ticker, self.safe_price)
        self.webserver.safe_prices(self.contract.ticker, self.safe_price)
        self.forwarder.publish(json.dumps({self.contract.ticker: self.safe_price}), tag=b'')

class AccountantExport(ComponentExport):
    def __init__(self, engine, safe_price_notifier, webserver_notifier):
        self.engine = engine
        self.safe_price_notifier = safe_price_notifier
        self.webserver_notifier = webserver_notifier
        ComponentExport.__init__(self, engine)

    @export
    @schema("rpc/engine.json#place_order")
    def place_order(self, order):
        return self.engine.place_order(Order(**order))

    @export
    @schema("rpc/engine.json#cancel_order")
    def cancel_order(self, id):
        return self.engine.cancel_order(id)

    @export
    @schema("rpc/engine.json#get_safe_price")
    def get_safe_price(self):
        return self.safe_price_notifier.safe_price

    @export
    @schema("rpc/engine.json#get_order_book")
    def get_order_book(self):
        return self.webserver_notifier.wire_book

class AdministratorExport(ComponentExport):
    def __init__(self, engine):
        self.engine = engine
        ComponentExport.__init__(self, engine)

    @export
    @schema("rpc/engine.json#get_order_book")
    def get_order_book(self):
        order_book = {OrderSide.name(side): {order.id: order.to_administrator() for order in orders}
                      for side, orders in self.engine.orderbook.iteritems()}
        return order_book


def main():
    log.startLogging(sys.stdout)
    session = database.make_session()
    ticker = args[0]

    try:
        contract = session.query(models.Contract).filter_by(ticker=ticker).one()
    except Exception, e:
        session.rollback()
        log.err("Cannot determine ticker id. %s" % e)
        log.err()
        raise e

    engine = Engine()
    administrator_export = AdministratorExport(engine)
    accountant_port = config.getint("engine", "accountant_base_port") + contract.id

    administrator_port = config.getint("engine", "administrator_base_port") + contract.id
    router_share_async(administrator_export, "tcp://127.0.0.1:%d" % administrator_port)

    logger = LoggingListener(engine, contract)
    acct = accountant.AccountantProxy("push",
                                            config.get("accountant", "engine_export"),
                                            config.getint("accountant", "engine_export_base_port"))
    accountant_notifier = AccountantNotifier(engine, acct, contract)
    webserver = push_proxy_async(config.get("webserver", "engine_export"))
    webserver_notifier = WebserverNotifier(engine, webserver, contract)


    watchdog(config.get("watchdog", "engine") %
             (config.getint("watchdog", "engine_base_port") + contract.id))

    forwarder = connect_publisher(config.get("safe_price_forwarder", "zmq_frontend_address"))

    safe_price_notifier = SafePriceNotifier(session, engine, acct, webserver, forwarder, contract)
    accountant_export = AccountantExport(engine, safe_price_notifier, webserver_notifier)
    router_share_async(accountant_export, "tcp://127.0.0.1:%d" % accountant_port)

    engine.add_listener(logger)
    engine.add_listener(accountant_notifier)
    engine.add_listener(webserver_notifier)
    engine.add_listener(safe_price_notifier)

    # Cancel all orders with some quantity_left that have been dispatched but not cancelled
    try:
        for order in session.query(models.Order).filter_by(
                is_cancelled=False).filter_by(
                contract_id=contract.id).filter(
                        models.Order.quantity_left > 0):
            log.msg("Cancelling order %d" % order.id)
            accountant.cancel_order(order.username, order.id)
    except Exception as e:
        session.rollback()
        raise e

    engine.notify_init()

    reactor.run()

